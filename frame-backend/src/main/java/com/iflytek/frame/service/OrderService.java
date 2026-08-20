package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.util.PageQuery;

import java.util.List;

public interface OrderService {
    Order getById(Long id, Long userId);
    PageResult<Order> myOrders(Long userId, PageQuery query, OrderStatus status);
    void cancel(Long id, Long userId);
    OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to);  // atomic conditional update

    /** Cancel a PAID order (admin/user) — flips PAID→CANCELLED, marks refund APPROVED, recredits stock. */
    void cancelPaid(Long id, Long userId);

    /** Request a refund on a SHIPPED order — flips SHIPPED→REFUND_PENDING, marks refund PENDING. */
    void requestRefund(Long id, Long userId, String reason);

    /** Buyer confirms receipt — flips SHIPPED→DELIVERED. */
    void confirmReceipt(Long id, Long userId);

    /**
     * Mark an order paid — atomic CAS PENDING→PAID recording {@code paid_at} in the same
     * transition (mirrors {@code confirmReceipt}). Ownership is the caller's responsibility
     * ({@code PaymentServiceImpl.pay()} authorizes via {@code getById} before invoking this).
     * Throws CONFLICT if the order isn't PENDING.
     */
    void markPaid(Long id);

    /** List line items for an order (ownership-checked). */
    List<OrderItem> listItems(Long id, Long userId);

    /** Scheduler: expire a stale PENDING order (CAS PENDING→CANCELLED + recredit stock). No-op if state changed. */
    void expireOrder(Long id);

    /** Scheduler: auto-complete a DELIVERED order (CAS DELIVERED→COMPLETED). No-op if state changed. */
    void completeOrder(Long id);
}
