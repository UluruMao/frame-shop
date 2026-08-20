package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.entity.Review;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    /** The review for a specific (order, product), or null if not yet reviewed. */
    Review selectByOrderAndProduct(@Param("orderId") Long orderId, @Param("productId") Long productId);

    /** All reviews attached to an order (to decide if the order is fully reviewed). */
    List<Review> selectByOrderId(@Param("orderId") Long orderId);
}
