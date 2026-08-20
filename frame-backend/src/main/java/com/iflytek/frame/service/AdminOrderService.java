package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.util.PageQuery;

/**
 * Admin-side order management — ship/deliver lifecycle + refund adjudication.
 *
 * <p>All transitions use atomic CAS updates ({@code updateStatusIf} /
 * {@code updateStatusAndTracking} / {@code updateStatusAndRefund}) so concurrent
 * admin/user actions cannot stomp each other; a 0-rows result throws CONFLICT.
 */
public interface AdminOrderService {
    /** Paginated list of all orders, optionally filtered by status. */
    PageResult<Order> listOrders(PageQuery q, OrderStatus status);

    /** Ship a PAID order (record tracking number + shipped_at). */
    void ship(Long id, String trackingNumber);

    /** Mark a SHIPPED order as delivered. */
    void deliver(Long id);

    /** Paginated list of orders pending refund adjudication (status=REFUND_PENDING). */
    PageResult<Order> refundRequests(PageQuery q);

    /** Approve a refund: REFUND_PENDING → REFUNDED + recredit stock. */
    void approveRefund(Long id);

    /** Deny a refund: REFUND_PENDING → SHIPPED + refund_status=DENIED. */
    void denyRefund(Long id);
}
