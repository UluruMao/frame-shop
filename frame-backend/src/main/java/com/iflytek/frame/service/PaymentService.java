package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;

/**
 * Mock payment service. The payment itself always succeeds (mentor's instruction);
 * the real concurrency guard is the atomic {@code flipStatus(PENDING → PAID)}
 * conditional update inside {@link OrderService}. If the order isn't PENDING,
 * {@code flipStatus} throws CONFLICT.
 */
public interface PaymentService {
    Order pay(Long orderId, Long userId);
}
