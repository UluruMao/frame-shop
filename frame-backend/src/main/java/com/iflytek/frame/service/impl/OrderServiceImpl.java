package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.PageResults;
import com.iflytek.frame.common.RefundStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.OrderStockReleaseService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    /** Redis key prefix mapping a checkout correlation id (millis) → the Order's DB PK. */
    private static final String ORDER_ID_MAP_PREFIX = "order_id_map:";

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final StringRedisTemplate redis;
    private final OrderStockReleaseService orderStockReleaseService;
    private final com.iflytek.frame.service.CoinService coinService;

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
    public PageResult<Order> myOrders(Long userId, PageQuery query, OrderStatus status) {
        Page<Order> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResults.of(result);
    }

    @Override
    @Transactional
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
        // Compensating stock release — recredit Redis + DB, clear the seckill lock.
        orderStockReleaseService.release(o);
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

    @Override
    @Transactional
    public void cancelPaid(Long id, Long userId) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        int rows = orderMapper.updateStatusAndRefund(realPk, OrderStatus.PAID, OrderStatus.CANCELLED,
                RefundStatus.APPROVED, null);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be cancelled in current state");
        }
        orderStockReleaseService.release(o);
        try {
            coinService.refundCoins(o.getId());
        } catch (Exception e) {
            log.warn("refundCoins({}) failed after cancel-paid: {}", o.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public void requestRefund(Long id, Long userId, String reason) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        // No stock recredit here — the item is in transit; recredit happens on admin approval.
        int rows = orderMapper.updateStatusAndRefund(realPk, OrderStatus.SHIPPED, OrderStatus.REFUND_PENDING,
                RefundStatus.PENDING, reason);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot request refund in current state");
        }
    }

    @Override
    @Transactional
    public void confirmReceipt(Long id, Long userId) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        // Atomic CAS flip SHIPPED→DELIVERED with delivered_at in one update (no race window).
        int rows = orderMapper.updateStatusAndDelivered(realPk, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
                LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be confirmed in current state");
        }
    }

    @Override
    @Transactional
    public void markPaid(Long id) {
        Long realPk = resolveOrderId(id);
        // Atomic CAS flip PENDING→PAID with paid_at in one update (no race window) — mirrors
        // confirmReceipt. Ownership is checked by the caller (PaymentServiceImpl.pay authorizes
        // via getById before invoking this).
        int rows = orderMapper.updateStatusAndPaid(realPk, OrderStatus.PENDING, OrderStatus.PAID,
                LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be paid in current state");
        }
    }

    @Override
    public List<OrderItem> listItems(Long id, Long userId) {
        Long realPk = resolveOrderId(id);
        Order o = orderMapper.selectById(realPk);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, realPk));
    }

    @Override
    @Transactional
    public void expireOrder(Long id) {
        Order o = orderMapper.selectById(id);
        if (o == null) {
            return; // already deleted / never existed — nothing to expire
        }
        int rows = orderMapper.updateStatusIf(id, OrderStatus.PENDING, OrderStatus.CANCELLED);
        if (rows == 0) {
            // State already advanced (paid/cancelled) — not an error for the scheduler.
            log.debug("expireOrder {} no-op: not PENDING (current={})", id, o.getStatus());
            return;
        }
        // Record why it was cancelled, then recredit stock.
        o.setStatus(OrderStatus.CANCELLED);
        o.setCancelReason("expired");
        orderMapper.updateById(o);
        orderStockReleaseService.release(o);
    }

    @Override
    @Transactional
    public void completeOrder(Long id) {
        Order o = orderMapper.selectById(id);
        if (o == null) {
            return;
        }
        int rows = orderMapper.updateStatusIf(id, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
        if (rows == 0) {
            // Not DELIVERED any more (already completed, or state regressed) — no-op.
            log.debug("completeOrder {} no-op: not DELIVERED (current={})", id, o.getStatus());
            return;
        }
        o.setStatus(OrderStatus.COMPLETED);
        o.setCompletedAt(LocalDateTime.now());
        orderMapper.updateById(o);
    }

    @Override
    public List<Order> listUnreviewedOrders(Long userId) {
        return orderMapper.selectUnreviewedOrders(userId);
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
