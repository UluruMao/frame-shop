package com.iflytek.frame.service.impl;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Mock payment: the payment itself always succeeds (mentor's instruction); the
 * atomic {@code markPaid} (PENDING → PAID) conditional update is the real guard.
 * If the order isn't PENDING, {@code markPaid} throws CONFLICT.
 */
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderService orderService;

    @Override
    public Order pay(Long orderId, Long userId) {
        // Authorize FIRST: getById resolves the correlation-id→PK via the order_id_map and
        // checks ownership (throws NOT_FOUND if the order isn't owned by this user). Mutating
        // before this check would let user A flip user B's PENDING order to PAID.
        orderService.getById(orderId, userId);
        // mock payment always succeeds (mentor's instruction); the atomic CAS flip is the guard.
        // markPaid sets paid_at in the same transition as the status flip (no race window), so
        // paid_at is populated on payment just like shipped_at/delivered_at/completed_at.
        orderService.markPaid(orderId);
        return orderService.getById(orderId, userId);
    }
}
