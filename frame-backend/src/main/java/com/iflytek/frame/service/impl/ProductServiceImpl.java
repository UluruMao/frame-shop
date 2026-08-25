package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.PageResults;
import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.service.StockGateService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceImpl.class);

    private final ProductMapper mapper;
    private final StringRedisTemplate redis;
    private final StockGateService stockGateService;
    private final ObjectMapper objectMapper;

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    private static final long CACHE_TTL_SECONDS = 30;

    @Override
    @SneakyThrows
    public Product getById(Long id) {
        String key = RedisKeys.product(id);
        String cached = null;
        try {
            cached = redis.opsForValue().get(key);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis read failed for key {} — falling through to DB: {}", key, e.getMessage());
        }
        if (cached != null) {
            return objectMapper.readValue(cached, Product.class);
        }
        Product p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(p), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis write failed for key {} — returning uncached result: {}", key, e.getMessage());
        }
        return p;
    }

    @Override
    public PageResult<ProductListItem> page(PageQuery query) {
        // Normalize sortField to the whitelist; anything else → null (XML defaults to create_time).
        String sf = query.getSortField();
        if (sf == null || !(sf.equals("price") || sf.equals("sales") || sf.equals("rating"))) {
            query.setSortField(null);
        }
        // Derive the SQL-layer values via PageQuery (single source of truth). sortOrderSql is the
        // ONLY ${} in the SQL, and it's derived from this whitelist — never raw user input (防SQL注入).
        query.setSortOrderSql(query.sortOrderSql());
        query.setKeywords(query.keywords());
        // ---- list cache (read-through) ----
        // Key built from the NORMALIZED query so logically-identical queries share a key.
        String cacheKey = listCacheKey(query);
        String cached = null;
        try {
            cached = redis.opsForValue().get(cacheKey);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis list-cache read failed for key {} — falling through to DB: {}", cacheKey, e.getMessage());
        }
        if (cached != null) {
            try {
                return objectMapper.readValue(cached,
                        objectMapper.getTypeFactory().constructParametricType(
                                com.iflytek.frame.common.PageResult.class,
                                com.iflytek.frame.dto.ProductListItem.class));
            } catch (Exception e) {
                log.warn("List-cache deserialization failed for key {} — falling through to DB: {}", cacheKey, e.getMessage());
            }
        }
        Page<ProductListItem> page = new Page<>(query.getCurrent(), query.getSize());
        IPage<ProductListItem> result = mapper.pageProducts(page, query);
        PageResult<ProductListItem> pr = PageResults.of(result);
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(pr), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
            redis.opsForSet().add(RedisKeys.productsPage("keys"), cacheKey);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis list-cache write failed for key {}: {}", cacheKey, e.getMessage());
        }
        return pr;
    }

    /** Deterministic cache key from the normalized PageQuery (post-normalization). */
    private String listCacheKey(PageQuery query) {
        String normalized = query.getCurrent() + "|" + query.getSize()
                + "|" + query.getSortField() + "|" + query.getSortOrderSql()
                + "|" + query.getCategory() + "|" + query.getKeywords();
        return RedisKeys.productsPage(Integer.toHexString(normalized.hashCode()));
    }

    /** Invalidate the entire product list cache (on any product mutation). */
    @Override
    public void invalidateListCache() {
        try {
            var keys = redis.opsForSet().members(RedisKeys.productsPage("keys"));
            if (keys != null && !keys.isEmpty()) {
                redis.delete(java.util.List.copyOf(keys));
            }
            redis.delete(RedisKeys.productsPage("keys"));
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis list-cache invalidation failed (non-fatal): {}", e.getMessage());
        }
    }

    @Override
    public ProductDetail getDetail(Long id) {
        ProductDetail d = mapper.findDetailById(id);
        if (d == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        return d;
    }

    @Override
    public java.util.List<String> listCategories() {
        return mapper.selectDistinctCategories();
    }

    @Override
    public Product create(ProductDTO dto) {
        Product p = dto.toEntity();
        mapper.insert(p);
        invalidateListCache();
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
        redis.delete(RedisKeys.product(id));
        // Gap 4: resync the Lua-gate stock:{id} key if it exists (active sale)
        if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.stock(id)))) {
            stockGateService.loadStock(id);
        }
        invalidateListCache();
        return existing;
    }

    @Override
    public void delete(Long id) {
        getById(id);
        mapper.deleteById(id);
        redis.delete(RedisKeys.product(id));
        invalidateListCache();
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
        redis.delete(RedisKeys.product(id));
        invalidateListCache();
        return imageUrl;
    }
}
