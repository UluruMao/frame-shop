package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;

/**
 * Mock payment service. The payment itself always succeeds (mentor's instruction);
 * the real concurrency guard is the atomic {@code markPaid} (PENDING → PAID)
 * conditional update inside {@link OrderService}, which also sets {@code paid_at}.
 * If the order isn't PENDING, {@code markPaid} throws CONFLICT.
 */
public interface PaymentService {
    Order pay(Long orderId, Long userId);
}
