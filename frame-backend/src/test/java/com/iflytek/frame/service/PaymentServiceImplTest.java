package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit test for the mock payment flow: {@code pay} delegates to the atomic
 * {@code flipStatus(PENDING → PAID)} conditional update — the real concurrency
 * guard — then returns the freshly-loaded {@link com.iflytek.frame.entity.Order}.
 *
 * <p>Per the mentor's instruction the mock payment always succeeds; the atomic
 * flip is the guard (if the order isn't PENDING, {@code flipStatus} throws CONFLICT).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {
    @Mock OrderService orderService;
    @InjectMocks PaymentServiceImpl paymentService;

    @Test
    void pay_flipsPendingToPaid() {
        when(orderService.flipStatus(7L, OrderStatus.PENDING, OrderStatus.PAID)).thenReturn(OrderStatus.PAID);

        paymentService.pay(7L, 99L);

        verify(orderService).flipStatus(7L, OrderStatus.PENDING, OrderStatus.PAID);
    }

    @Test
    void pay_returnsTheFlippedOrder() {
        com.iflytek.frame.entity.Order paid = new com.iflytek.frame.entity.Order();
        paid.setId(7L);
        paid.setStatus(OrderStatus.PAID);
        when(orderService.flipStatus(7L, OrderStatus.PENDING, OrderStatus.PAID)).thenReturn(OrderStatus.PAID);
        when(orderService.getById(7L, 99L)).thenReturn(paid);

        com.iflytek.frame.entity.Order result = paymentService.pay(7L, 99L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderService).getById(7L, 99L);
    }
}
