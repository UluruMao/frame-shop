package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import org.springframework.web.multipart.MultipartFile;

public interface ProductService {
    Product getById(Long id);
    PageResult<Product> page(PageQuery query);
    Product create(ProductDTO dto);
    Product update(Long id, ProductDTO dto);
    void delete(Long id);
    String uploadImage(Long id, MultipartFile file);
}
