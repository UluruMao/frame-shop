package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.RefundStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.impl.AdminOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminOrderServiceImpl} — the admin-side order state machine.
 *
 * <p>Each transition is a CAS: {@code ship} PAID→SHIPPED (with tracking),
 * {@code deliver} SHIPPED→DELIVERED, {@code approveRefund} REFUND_PENDING→REFUNDED
 * (recredits stock), {@code denyRefund} REFUND_PENDING→SHIPPED. A 0-rows result
 * (concurrent mutation / wrong from-state) throws CONFLICT.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOrderServiceTest {

    @Mock OrderMapper orderMapper;
    @Mock OrderStockReleaseService orderStockReleaseService;
    @Mock CoinService coinService;

    private AdminOrderServiceImpl adminOrderService;

    @BeforeEach
    void setUp() {
        adminOrderService = new AdminOrderServiceImpl(orderMapper, orderStockReleaseService, coinService);
    }

    // ===== ship: PAID → SHIPPED =====

    @Test
    void ship_paidToShipped_recordsTracking() {
        when(orderMapper.updateStatusAndTracking(anyLong(), any(), any(), any(), any())).thenReturn(1);

        adminOrderService.ship(1L, "TRACK-XYZ");

        verify(orderMapper).updateStatusAndTracking(eq(1L), eq(OrderStatus.PAID),
                eq(OrderStatus.SHIPPED), eq("TRACK-XYZ"), any());
    }

    @Test
    void ship_notPaid_throwsConflict() {
        when(orderMapper.updateStatusAndTracking(anyLong(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> adminOrderService.ship(1L, "TRACK-XYZ"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be shipped");
    }

    // ===== deliver: SHIPPED → DELIVERED =====

    @Test
    void deliver_shippedToDelivered() {
        when(orderMapper.updateStatusAndDelivered(anyLong(), any(), any(), any())).thenReturn(1);

        adminOrderService.deliver(1L);

        verify(orderMapper).updateStatusAndDelivered(eq(1L), eq(OrderStatus.SHIPPED),
                eq(OrderStatus.DELIVERED), any());
    }

    @Test
    void deliver_notShipped_throwsConflict() {
        when(orderMapper.updateStatusAndDelivered(anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> adminOrderService.deliver(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be delivered");
    }

    // ===== approveRefund: REFUND_PENDING → REFUNDED + stock recredit =====

    @Test
    void approveRefund_refundPendingToRefunded_recreditsStock() {
        Order o = new Order();
        o.setId(1L);
        o.setUserId(7L);
        o.setStatus(OrderStatus.REFUND_PENDING);
        when(orderMapper.selectById(1L)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED,
                RefundStatus.APPROVED, null)).thenReturn(1);

        adminOrderService.approveRefund(1L);

        verify(orderMapper).updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED,
                RefundStatus.APPROVED, null);
        verify(orderStockReleaseService).release(any());
    }

    @Test
    void approveRefund_notRefundPending_throwsConflict() {
        Order o = new Order();
        o.setId(1L);
        o.setStatus(OrderStatus.SHIPPED); // not REFUND_PENDING
        when(orderMapper.selectById(1L)).thenReturn(o);
        when(orderMapper.updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED,
                RefundStatus.APPROVED, null)).thenReturn(0);

        assertThatThrownBy(() -> adminOrderService.approveRefund(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be refunded");
        verify(orderStockReleaseService, never()).release(any());
    }

    @Test
    void approveRefund_orderMissing_throwsNotFound() {
        when(orderMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> adminOrderService.approveRefund(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("order not found");
    }

    // ===== denyRefund: REFUND_PENDING → SHIPPED =====

    @Test
    void denyRefund_refundPendingToShipped() {
        when(orderMapper.updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.SHIPPED,
                RefundStatus.DENIED, null)).thenReturn(1);

        adminOrderService.denyRefund(1L);

        verify(orderMapper).updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.SHIPPED,
                RefundStatus.DENIED, null);
        // No stock change on deny
        verify(orderStockReleaseService, never()).release(any());
    }

    @Test
    void denyRefund_notRefundPending_throwsConflict() {
        when(orderMapper.updateStatusAndRefund(1L, OrderStatus.REFUND_PENDING, OrderStatus.SHIPPED,
                RefundStatus.DENIED, null)).thenReturn(0);

        assertThatThrownBy(() -> adminOrderService.denyRefund(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be denied refund");
    }

    // helper for arg matchers used above
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
