package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * Conditional stock decrement — DB-level mutex/backstop.
     * Returns rows affected (0 if stock &lt; qty → caller treats as failure).
     */
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);

    void updateImageUrl(@Param("id") Long id, @Param("imageUrl") String imageUrl);
}
