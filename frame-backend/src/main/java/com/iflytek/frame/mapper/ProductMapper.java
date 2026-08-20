package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iflytek.frame.dto.ProductDetail;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.util.PageQuery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * Conditional stock decrement — DB-level mutex/backstop.
     * Returns rows affected (0 if stock &lt; qty → caller treats as failure).
     */
    int decrementStock(@Param("id") Long id, @Param("qty") int qty);

    /**
     * Reverse a prior {@link #decrementStock} — compensating action used when a
     * checkout must roll back stock it already claimed (sold-out on a later item,
     * or a duplicate idempotency-key insert). Not a gate: always succeeds.
     */
    int recreditStock(@Param("id") Long id, @Param("qty") int qty);

    void updateImageUrl(@Param("id") Long id, @Param("imageUrl") String imageUrl);

    /** Paginated catalog query with keyword + category filter and price/sales/rating sort. */
    IPage<ProductListItem> pageProducts(IPage<?> page, @Param("q") PageQuery q);

    /** Single product detail with sold + avgRating aggregates. */
    ProductDetail findDetailById(@Param("id") Long id);

    /** Distinct non-null categories for the filter dropdown. */
    List<String> selectDistinctCategories();
}
