package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.DemoProductDTO;
import com.iflytek.frame.entity.DemoProduct;
import com.iflytek.frame.util.PageQuery;

/**
 * Demo CRUD service interface. Kept as an interface so caching/transaction
 * proxies and future queue producers can wrap the impl without touching callers.
 */
public interface DemoProductService {

    DemoProduct getById(Long id);

    PageResult<DemoProduct> page(PageQuery query);

    DemoProduct create(DemoProductDTO dto);

    DemoProduct update(Long id, DemoProductDTO dto);

    void delete(Long id);
}
