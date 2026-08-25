package com.iflytek.frame.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compensating stock release for a cancelled / refunded order.
 *
 * <p>Recr edits both the Redis flash-sale stock ({@code stock:{pid}}) and the DB stock
 * ({@code product.stock}), and clears the one-per-user seckill lock
 * ({@code seckill:{pid}:{userId}}). Redis failures are warn-logged, never thrown —
 * the DB recredit + status flip must still complete (a Redis outage must not block
 * the order state machine). This is shared by {@code OrderServiceImpl.cancel},
 * {@code cancelPaid}, {@code expireOrder} and {@code AdminOrderServiceImpl.approveRefund}
 * so the recredit logic is defined exactly once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderStockReleaseService {

    private final OrderItemMapper orderItemMapper;
    private final StockGateService stockGateService;
    private final ProductMapper productMapper;
    private final StringRedisTemplate redis;

    /**
     * Release (recredit) the stock claimed by an order's line items.
     * Safe to call for any order status — loads the snapshot of items and recredits each.
     */
    public void release(Order o) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, o.getId()));
        for (OrderItem it : items) {
            try {
                stockGateService.recredit(it.getProductId(), it.getQuantity());      // Redis (flash-sale key; no-op if absent)
            } catch (RedisConnectionFailureException e) {
                log.warn("Redis recredit failed for product {} — DB recredit continues: {}",
                        it.getProductId(), e.getMessage());
            }
            productMapper.recreditStock(it.getProductId(), it.getQuantity());        // DB (always)
            try {
                redis.delete(RedisKeys.seckill(it.getProductId(), o.getUserId()));  // release one-per-user lock
            } catch (RedisConnectionFailureException e) {
                log.warn("Redis seckill-delete failed for product {} — non-fatal: {}",
                        it.getProductId(), e.getMessage());
            }
        }
    }
}
