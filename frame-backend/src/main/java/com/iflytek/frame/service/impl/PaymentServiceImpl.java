package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Mock payment: the payment itself always succeeds (mentor's instruction); the
 * atomic {@code flipStatus(PENDING → PAID)} conditional update is the real guard.
 * If the order isn't PENDING, {@code flipStatus} throws CONFLICT.
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
        // mock payment always succeeds (mentor's instruction); the atomic flip is the guard
        orderService.flipStatus(orderId, OrderStatus.PENDING, OrderStatus.PAID);
        return orderService.getById(orderId, userId);
    }
}
