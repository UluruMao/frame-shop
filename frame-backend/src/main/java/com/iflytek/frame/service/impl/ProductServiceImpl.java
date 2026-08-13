package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    private static final long CACHE_TTL_SECONDS = 30;

    @Override
    @SneakyThrows
    public Product getById(Long id) {
        String key = "product:" + id;
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return objectMapper.readValue(cached, Product.class);
        }
        Product p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        redis.opsForValue().set(key, objectMapper.writeValueAsString(p), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return p;
    }

    @Override
    public PageResult<Product> page(PageQuery query) {
        Page<Product> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(Product::getCreateTime);
        IPage<Product> result = mapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public Product create(ProductDTO dto) {
        Product p = dto.toEntity();
        mapper.insert(p);
        return p;
    }

    @Override
    public Product update(Long id, ProductDTO dto) {
        Product existing = getById(id);
        existing.setName(dto.getName());
        existing.setPriceInCents(dto.getPriceInCents());
        existing.setStock(dto.getStock());
        existing.setImageUrl(dto.getImageUrl());
        existing.setCategory(dto.getCategory());
        existing.setFlashSale(dto.isFlashSale());
        existing.setFlashSaleAt(dto.getFlashSaleAt());
        existing.setDescription(dto.getDescription());
        mapper.updateById(existing);
        redis.delete("product:" + id);
        return existing;
    }

    @Override
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
        redis.delete("product:" + id);
    }

    @Override
    @SneakyThrows
    public String uploadImage(Long id, MultipartFile file) {
        getById(id);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "file is empty");
        }
        String original = file.getOriginalFilename();
        String ext = StringUtils.hasText(original) && original.contains(".")
                ? original.substring(original.lastIndexOf('.')) : "";
        String filename = "product-" + id + "-" + UUID.randomUUID() + ext;
        Path dir = Path.of(uploadDir);
        Files.createDirectories(dir);
        file.transferTo(dir.resolve(filename).toFile());
        String imageUrl = "/uploads/" + filename;
        mapper.updateImageUrl(id, imageUrl);
        redis.delete("product:" + id);
        return imageUrl;
    }
}
