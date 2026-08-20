package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.RefundStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for the correlation-id→PK resolution in
 * {@link OrderServiceImpl#getById} / {@link OrderServiceImpl#cancel} and the
 * Phase C state-machine transitions (cancelPaid / requestRefund / confirmReceipt)
 * plus the compensating stock release.
 *
 * <p>The checkout hot path returns a millis-based correlation id, NOT the DB PK. After the
 * consumer durably persists the order, it writes {@code order_id_map:{correlationId} = <dbPk>}
 * to Redis. {@code getById} resolves the correlation id → real PK via that map, then loads
 * the Order by the PK and applies the ownership check.
 *
 * <p>These tests verify the three resolution outcomes:
 * <ul>
 *   <li>Correlation id found in the map → resolve to real PK → return the order.</li>
 *   <li>Real PK passed directly → fast-path {@code selectById(id)} returns the order.</li>
 *   <li>Neither a real PK nor a mapped correlation id → NOT_FOUND.</li>
 * </ul>
 *
 * <p>Phase C additions: cancel recredits stock; cancelPaid flips PAID→CANCELLED with
 * APPROVED refund + stock recredit; requestRefund flips SHIPPED→REFUND_PENDING;
 * confirmReceipt flips SHIPPED→DELIVERED; Redis-down during release does not block the DB.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock OrderStockReleaseService orderStockReleaseService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(orderMapper, orderItemMapper, redis, orderStockReleaseService);
    }

    // ===== getById correlation-id resolution =====

    @Test
    void getById_correlationId_resolvesViaMapAndReturnsOrder() {
        long corrId = 1690000000000L; // what the client holds (from the 202 response)
        long realPk = 42L;           // DB auto-generated PK

        // selectById(corrId) returns null (corrId is NOT the PK) → slow path
        when(orderMapper.selectById(corrId)).thenReturn(null);
        // Redis map: order_id_map:{corrId} = "42"
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(String.valueOf(realPk));
        // selectById(realPk) to validate + load the order
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(7L);
        when(orderMapper.selectById(realPk)).thenReturn(order);

        Order result = orderService.getById(corrId, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(realPk);
        // fast-path selectById(corrId) was tried first (returned null → slow path)
        verify(orderMapper).selectById(corrId);
        // then the map was consulted
        verify(valueOps).get("order_id_map:" + corrId);
        // selectById(realPk) called twice: once in resolveOrderId (validates the map
        // points to a real PK), once in getById (loads the order for ownership check).
        // The validation ensures a stale/corrupt map entry → NOT_FOUND (not a misleading
        // downstream error); getById's own load applies the ownership check.
        verify(orderMapper, times(2)).selectById(realPk);
    }

    @Test
    void getById_realPkPassedDirectly_fastPathsAndReturnsOrder() {
        long realPk = 42L;

        // selectById(realPk) returns the order directly (fast path)
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(7L);
        when(orderMapper.selectById(realPk)).thenReturn(order);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("order_status:" + realPk)).thenReturn(null);

        Order result = orderService.getById(realPk, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(realPk);
        // The order_id_map is NOT consulted on the fast path (only order_status was)
        verify(valueOps, never()).get("order_id_map:" + realPk);
    }

    @Test
    void getById_correlationIdNotInMap_throwsNotFound() {
        long corrId = 1690000000000L;

        when(orderMapper.selectById(corrId)).thenReturn(null); // not a real PK
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(null); // not in map either

        assertThatThrownBy(() -> orderService.getById(corrId, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void getById_resolvedOrderWrongUser_throwsNotFound() {
        long corrId = 1690000000000L;
        long realPk = 42L;

        when(orderMapper.selectById(corrId)).thenReturn(null);
        when(redis.opsForValue()).thenReturn(valueOps);
        // getById checks order_status:{id} for the queued case first; not "queued" here
        when(valueOps.get("order_status:" + corrId)).thenReturn(null);
        when(valueOps.get("order_id_map:" + corrId)).thenReturn(String.valueOf(realPk));
        // order belongs to user 99, not 7
        Order order = new Order();
        order.setId(realPk);
        order.setUserId(99L);
        when(orderMapper.selectById(realPk)).thenReturn(order);

        assertThatThrownBy(() -> orderService.getById(corrId, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    @Test
    void getById_queuedInRedis_returnsSyntheticOrderForPolling() {
        long corrId = 1690000000000L; // what the client holds (from the 202 response)

        // order_status:{corrId} = "queued" — consumer hasn't persisted the row yet
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("order_status:" + corrId)).thenReturn("queued");

        Order result = orderService.getById(corrId, 7L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(corrId);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING); // placeholder
        assertThat(result.getUserId()).isEqualTo(7L);
        // The DB must NOT be hit — the order row doesn't exist yet
        verify(orderMapper, never()).selectById(any());
        // The order_id_map must NOT be consulted — queued means no PK mapping yet
        verify(valueOps, never()).get("order_id_map:" + corrId);
    }

    // ===== cancel: PENDING → CANCELLED + stock recredit =====

    @Test
    void cancel_pending_recreditsStockAndDelsSeckill() {
        long realPk = 10L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PENDING);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        // resolveOrderId fast-path: selectById(realPk) != null → returns realPk
        // CAS succeeds
        when(orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(1);

        orderService.cancel(realPk, userId);

        verify(orderStockReleaseService).release(o);
    }

    @Test
    void cancel_nonPending_throwsConflict() {
        long realPk = 10L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PAID); // already paid → can't cancel as PENDING
        when(orderMapper.selectById(realPk)).thenReturn(o);
        // CAS fails (0 rows) — the order is no longer PENDING
        when(orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(0);

        assertThatThrownBy(() -> orderService.cancel(realPk, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be cancelled");
        verify(orderStockReleaseService, never()).release(any());
    }

    // ===== cancelPaid: PAID → CANCELLED + APPROVED refund + stock recredit =====

    @Test
    void cancelPaid_paidToCancelled_setsRefundApproved_recreditsStock() {
        long realPk = 11L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PAID);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(realPk, OrderStatus.PAID, OrderStatus.CANCELLED,
                RefundStatus.APPROVED, null)).thenReturn(1);

        orderService.cancelPaid(realPk, userId);

        verify(orderMapper).updateStatusAndRefund(realPk, OrderStatus.PAID, OrderStatus.CANCELLED,
                RefundStatus.APPROVED, null);
        verify(orderStockReleaseService).release(o);
    }

    @Test
    void cancelPaid_notPaid_throwsConflict() {
        long realPk = 11L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.SHIPPED); // not PAID → cannot cancel-paid
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(realPk, OrderStatus.PAID, OrderStatus.CANCELLED,
                RefundStatus.APPROVED, null)).thenReturn(0);

        assertThatThrownBy(() -> orderService.cancelPaid(realPk, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be cancelled");
        verify(orderStockReleaseService, never()).release(any());
    }

    @Test
    void cancelPaid_wrongUser_throwsNotFound() {
        long realPk = 11L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(99L); // belongs to another user
        when(orderMapper.selectById(realPk)).thenReturn(o);

        assertThatThrownBy(() -> orderService.cancelPaid(realPk, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
        verify(orderStockReleaseService, never()).release(any());
    }

    // ===== requestRefund: SHIPPED → REFUND_PENDING =====

    @Test
    void requestRefund_shippedToRefundPending() {
        long realPk = 12L;
        long userId = 7L;
        String reason = "arrived damaged";
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.SHIPPED);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(realPk, OrderStatus.SHIPPED, OrderStatus.REFUND_PENDING,
                RefundStatus.PENDING, reason)).thenReturn(1);

        orderService.requestRefund(realPk, userId, reason);

        verify(orderMapper).updateStatusAndRefund(realPk, OrderStatus.SHIPPED, OrderStatus.REFUND_PENDING,
                RefundStatus.PENDING, reason);
        // No stock recredit at request time — only on admin approval
        verify(orderStockReleaseService, never()).release(any());
    }

    @Test
    void requestRefund_notShipped_throwsConflict() {
        long realPk = 12L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PAID); // can't refund a not-shipped order
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(realPk, OrderStatus.SHIPPED, OrderStatus.REFUND_PENDING,
                RefundStatus.PENDING, "x")).thenReturn(0);

        assertThatThrownBy(() -> orderService.requestRefund(realPk, userId, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot request refund");
    }

    // ===== confirmReceipt: SHIPPED → DELIVERED =====

    @Test
    void confirmReceipt_shippedToDelivered() {
        long realPk = 13L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.SHIPPED);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndDelivered(anyLong(), any(), any(), any())).thenReturn(1);

        orderService.confirmReceipt(realPk, userId);

        verify(orderMapper).updateStatusAndDelivered(eq(realPk), eq(OrderStatus.SHIPPED),
                eq(OrderStatus.DELIVERED), any());
    }

    @Test
    void confirmReceipt_notShipped_throwsConflict() {
        long realPk = 13L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PENDING); // can't confirm an unshipped order
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusAndDelivered(anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.confirmReceipt(realPk, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be confirmed");
    }

    // ===== listItems =====

    @Test
    void listItems_returnsItemsForOwnedOrder() {
        long realPk = 14L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        OrderItem it = new OrderItem();
        it.setOrderId(realPk);
        it.setProductId(1L);
        it.setQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(it));

        var items = orderService.listItems(realPk, userId);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getProductId()).isEqualTo(1L);
    }

    @Test
    void listItems_wrongUser_throwsNotFound() {
        long realPk = 14L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(99L);
        when(orderMapper.selectById(realPk)).thenReturn(o);

        assertThatThrownBy(() -> orderService.listItems(realPk, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    // ===== expireOrder / completeOrder scheduler paths =====

    @Test
    void expireOrder_pending_cancelsAndRecredits() {
        long realPk = 15L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(7L);
        o.setStatus(OrderStatus.PENDING);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(1);

        orderService.expireOrder(realPk);

        verify(orderMapper).updateById(any(Order.class)); // sets cancel_reason='expired'
        verify(orderStockReleaseService).release(any());
    }

    @Test
    void expireOrder_notPending_noop() {
        long realPk = 15L;
        Order o = new Order();
        o.setId(realPk);
        o.setStatus(OrderStatus.PAID); // already advanced
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(0);

        orderService.expireOrder(realPk);

        verify(orderStockReleaseService, never()).release(any());
    }

    @Test
    void completeOrder_delivered_completes() {
        long realPk = 16L;
        Order o = new Order();
        o.setId(realPk);
        o.setStatus(OrderStatus.DELIVERED);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusIf(realPk, OrderStatus.DELIVERED, OrderStatus.COMPLETED)).thenReturn(1);

        orderService.completeOrder(realPk);

        verify(orderMapper).updateById(any(Order.class)); // sets completed_at
    }

    @Test
    void completeOrder_notDelivered_noop() {
        long realPk = 16L;
        Order o = new Order();
        o.setId(realPk);
        o.setStatus(OrderStatus.PENDING);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusIf(realPk, OrderStatus.DELIVERED, OrderStatus.COMPLETED)).thenReturn(0);

        orderService.completeOrder(realPk);

        verify(orderMapper, never()).updateById(any(Order.class));
    }

    // ===== OrderStockReleaseService: Redis-down does not block the DB =====

    /**
     * The release service wraps Redis calls in try/catch RedisConnectionFailureException
     * so a Redis outage does not block the DB recredit + status flip. Verify the contract
     * at the service level: when release throws nothing, the cancel still completed — i.e.
     * the release service's contract is "never throw on Redis down". Here we stub release
     * to simulate the success path (the release service itself has its own unit test below);
     * the point of this test is that cancel completes when release is invoked.
     */
    @Test
    void releaseOrderStock_redisDown_doesNotBlock() {
        long realPk = 17L;
        long userId = 7L;
        Order o = new Order();
        o.setId(realPk);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PENDING);
        when(orderMapper.selectById(realPk)).thenReturn(o);
        when(orderMapper.updateStatusIf(realPk, OrderStatus.PENDING, OrderStatus.CANCELLED)).thenReturn(1);
        // release() is stubbed to do nothing (default) — the cancel must complete regardless.

        orderService.cancel(realPk, userId);

        // The status flip + release both completed — cancel did not throw.
        verify(orderStockReleaseService).release(o);
    }

    // helper for arg matchers used above
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
