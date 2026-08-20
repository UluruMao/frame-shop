package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.impl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit test for the mock payment flow: {@code pay} delegates to the atomic
 * {@code markPaid} (PENDING → PAID) conditional update — the real concurrency
 * guard — then returns the freshly-loaded {@link Order}.
 *
 * <p>Per the mentor's instruction the mock payment always succeeds; the atomic
 * CAS flip is the guard (if the order isn't PENDING, {@code markPaid} throws CONFLICT).
 * {@code markPaid} sets {@code paid_at} in the same transition as the status flip,
 * so the paid timestamp is populated on payment just like the shipped/delivered/completed
 * timestamps on their transitions.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {
    @Mock OrderService orderService;
    @InjectMocks PaymentServiceImpl paymentService;

    @Test
    void pay_marksOrderPaid() {
        // markPaid is a void CAS method; doThrow lets the no-arg stub default to do-nothing (success).
        paymentService.pay(7L, 99L);

        verify(orderService).markPaid(7L);
        // flipStatus must NOT be used anymore — paid_at is set via markPaid's CAS update.
        verify(orderService, never()).flipStatus(anyLong(), any(), any());
    }

    @Test
    void pay_returnsThePaidOrder() {
        Order paid = new Order();
        paid.setId(7L);
        paid.setStatus(OrderStatus.PAID);
        when(orderService.getById(7L, 99L)).thenReturn(paid);

        Order result = paymentService.pay(7L, 99L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(7L);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PAID);
        // getById is called twice: once to authorize (ownership check) and once to return
        // the freshly-paid order.
        verify(orderService, times(2)).getById(7L, 99L);
        verify(orderService).markPaid(7L);
    }

    @Test
    void pay_markPaidConflict_propagatesAsBusinessException() {
        // The ownership check passes (getById returns a non-null owned order), but markPaid's
        // CAS finds the order no longer PENDING (e.g. a duplicate-pay race) → CONFLICT.
        Order owned = new Order();
        owned.setId(7L);
        owned.setStatus(OrderStatus.PAID);
        when(orderService.getById(7L, 99L)).thenReturn(owned);
        doThrow(new BusinessException(ResultCode.CONFLICT, "order cannot be paid in current state"))
                .when(orderService).markPaid(7L);

        assertThatThrownBy(() -> paymentService.pay(7L, 99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be paid");
        // The re-fetch getById (the return statement) must NOT be reached when markPaid throws.
        verify(orderService).markPaid(7L);
        verify(orderService, times(1)).getById(7L, 99L);  // only the authorize call, not the return fetch
        verify(orderService, never()).flipStatus(anyLong(), any(), any());
    }
}
