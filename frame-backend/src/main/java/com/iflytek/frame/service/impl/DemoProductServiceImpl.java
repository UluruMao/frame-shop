package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.DemoProductDTO;
import com.iflytek.frame.entity.DemoProduct;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.DemoProductMapper;
import com.iflytek.frame.service.DemoProductService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Demo CRUD implementation. MyBatis-Plus {@link DemoProductMapper} (extends BaseMapper)
 * supplies the actual SQL; this layer adds business rules (validation, not-found handling).
 */
@Service
@RequiredArgsConstructor
public class DemoProductServiceImpl implements DemoProductService {

    private final DemoProductMapper mapper;

    @Override
    public DemoProduct getById(Long id) {
        DemoProduct p = mapper.selectById(id);
        if (p == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + id);
        }
        return p;
    }

    @Override
    public PageResult<DemoProduct> page(PageQuery query) {
        Page<DemoProduct> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<DemoProduct> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getSortField())
                && "asc".equalsIgnoreCase(query.getSortOrder())) {
            wrapper.orderByAsc(DemoProduct::getCreateTime); // simplistic; extend as needed
        } else {
            wrapper.orderByDesc(DemoProduct::getCreateTime);
        }
        IPage<DemoProduct> result = mapper.selectPage(page, wrapper);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public DemoProduct create(DemoProductDTO dto) {
        DemoProduct p = dto.toEntity();
        mapper.insert(p);
        return p;
    }

    @Override
    public DemoProduct update(Long id, DemoProductDTO dto) {
        DemoProduct existing = getById(id); // throws if missing
        existing.setName(dto.getName());
        existing.setPrice(dto.getPrice());
        existing.setStock(dto.getStock());
        existing.setDescription(dto.getDescription());
        mapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long id) {
        getById(id); // throws if missing
        mapper.deleteById(id);
    }
}
