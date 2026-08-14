package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** Redis key prefix mapping a checkout correlation id (millis) → the Order's DB PK. */
    private static final String ORDER_ID_MAP_PREFIX = "order_id_map:";

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redis;

    /**
     * Resolve the id the client holds (a DB PK or the millis-based correlation id from the
     * 202 response) to the actual Order row.
     *
     * <p>The checkout hot path returns a correlation id ({@code System.currentTimeMillis()}),
     * NOT the DB PK — the consumer lets the DB auto-generate the PK. After durable-write, the
     * consumer publishes {@code order_id_map:{correlationId} = <dbPk>} to Redis. This method:
     * <ol>
     *   <li>If {@code order_status:{id} = "queued"} in Redis (consumer hasn't persisted yet),
     *       returns a synthetic Order with {@code status=PENDING} so the frontend can poll.</li>
     *   <li>Otherwise tries {@code selectById(id)} first — handles a client passing a real PK.</li>
     *   <li>If null, looks up {@code order_id_map:{id}} → the real PK → {@code selectById(realPk)}.</li>
     *   <li>If all miss, throws NOT_FOUND.</li>
     * </ol>
     * The ownership check ({@code userId} matches) runs on the resolved row.
     */
    @Override
    public Order getById(Long id, Long userId) {
        // Queued case: the checkout hot path returned a correlation id (millis) and
        // set order_status:{id} = "queued" in Redis. The consumer hasn't persisted the
        // Order row yet, so neither selectById(id) nor order_id_map:{id} will resolve.
        // Return a synthetic Order so the frontend can poll for "pending"/"failed".
        String statusKey = "order_status:" + id;
        String cachedStatus = redis.opsForValue().get(statusKey);
        if ("queued".equals(cachedStatus)) {
            Order queued = new Order();
            queued.setId(id);
            queued.setStatus(OrderStatus.PENDING); // placeholder; frontend polls for pending/failed
            queued.setUserId(userId);
            return queued;
        }

        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return o;
    }

    @Override
    public PageResult<Order> myOrders(Long userId, PageQuery query) {
        Page<Order> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public void cancel(Long id, Long userId) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        // atomic: only PENDING can be cancelled
        int rows = orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be cancelled in current state");
        }
    }

    @Override
    public OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to) {
        Long realPk = resolveOrderId(id);
        int rows = orderMapper.updateStatusIf(realPk, from, to);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order state transition not allowed");
        }
        return to;
    }

    /**
     * Resolve a correlation id (or real PK) to the DB PK, or throw NOT_FOUND.
     *
     * <p>The checkout hot path returns a millis-based correlation id, NOT the DB PK (the
     * consumer lets the DB auto-generate it). After durable-write, the consumer publishes
     * {@code order_id_map:{correlationId} = <dbPk>} to Redis. This method:
     * <ol>
     *   <li>Tries {@code selectById(id)} first — handles a real PK passed directly.</li>
     *   <li>If null, looks up {@code order_id_map:{id}} → the real PK → validates it exists.</li>
     *   <li>If both miss, throws NOT_FOUND.</li>
     * </ol>
     */
    private Long resolveOrderId(Long correlationOrPk) {
        // Fast path: the id IS the DB PK (client passed a real PK, or the legacy sync path).
        if (orderMapper.selectById(correlationOrPk) != null) {
            return correlationOrPk;
        }
        // Slow path: the id is the millis correlation id → look up the real PK in Redis.
        String mapped = redis.opsForValue().get(ORDER_ID_MAP_PREFIX + correlationOrPk);
        if (mapped != null) {
            try {
                Long realPk = Long.valueOf(mapped);
                if (orderMapper.selectById(realPk) != null) {
                    return realPk;
                }
            } catch (NumberFormatException ignored) {
                // corrupt map value — fall through to NOT_FOUND
            }
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
    }
}
