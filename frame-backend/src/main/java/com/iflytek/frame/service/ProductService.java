package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ProductService {
    Product getById(Long id);
    PageResult<ProductListItem> page(PageQuery query);
    ProductDetail getDetail(Long id);
    List<String> listCategories();
    Product create(ProductDTO dto);
    Product update(Long id, ProductDTO dto);
    void delete(Long id);
    String uploadImage(Long id, MultipartFile file);

    /**
     * Invalidate the entire product list cache (on any product-stock mutation).
     *
     * <p>Exposed so {@code StreamConsumerService} can clear stale {@code stock} values from the
     * list cache after the DB-backstop {@code decrementStock}/{@code recredit} paths (which
     * bypass {@code ProductServiceImpl}'s own mutation methods). Redis down is non-fatal.
     */
    void invalidateListCache();
}
