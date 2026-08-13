package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * Look up an order by its idempotency key — used to return the existing order
     * when a duplicate checkout request hits the {@code uk_idempotency_key} UNIQUE
     * constraint.
     */
    Order selectByIdempotencyKey(@Param("key") String key);

    /**
     * Atomic conditional status update — only flips the row if its current status
     * equals {@code from}. Returns rows affected (0 → transition not allowed).
     * Backs {@code cancel} (PENDING→CANCELLED) and {@code flipStatus}.
     */
    int updateStatusIf(@Param("id") Long id,
                       @Param("from") OrderStatus from,
                       @Param("to") OrderStatus to);
}
