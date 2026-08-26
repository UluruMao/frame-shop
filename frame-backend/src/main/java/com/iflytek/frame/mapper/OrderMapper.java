package com.iflytek.frame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.RefundStatus;
import com.iflytek.frame.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * Look up an order by its idempotency key — used to return the existing order
     * when a duplicate checkout request hits the {@code uk_idempotency_key} UNIQUE
     * constraint.
     */
    Order selectByIdempotencyKey(@Param("key") String key);

    /**
     * Atomic conditional status update — only flips the row if its current status
     * equals {@code from}. Returns rows affected (0 → transition not allowed).
     * Backs {@code cancel} (PENDING→CANCELLED) and {@code flipStatus}.
     */
    int updateStatusIf(@Param("id") Long id,
                       @Param("from") OrderStatus from,
                       @Param("to") OrderStatus to);

    /**
     * PENDING orders older than {@code cutoff} — candidates for the expire scheduler.
     */
    List<Order> selectPendingBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * DELIVERED orders older than {@code cutoff} — candidates for the auto-complete scheduler.
     */
    List<Order> selectDeliveredBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Orders in {DELIVERED, COMPLETED} for a user that have ≥1 unreviewed line item —
     * backs the 未评价 tab (UC010). An order is "unreviewed" if any of its order_item
     * product_ids has no matching review row.
     */
    List<Order> selectUnreviewedOrders(@Param("userId") Long userId);

    /**
     * Atomic CAS flip PAID→SHIPPED recording the tracking number + shipped timestamp.
     * Returns rows affected (0 → transition not allowed).
     */
    int updateStatusAndTracking(@Param("id") Long id,
                                @Param("from") OrderStatus from,
                                @Param("to") OrderStatus to,
                                @Param("tracking") String trackingNumber,
                                @Param("shippedAt") LocalDateTime shippedAt);

    /**
     * Atomic CAS flip recording both the new status and the refund status/reason.
     * Used by {@code cancelPaid}, {@code requestRefund}, {@code approveRefund}, {@code denyRefund}.
     * {@code reason} is only written when non-null (the {@code <if>} guard avoids clobbering
     * an existing reason on the deny path).
     */
    int updateStatusAndRefund(@Param("id") Long id,
                              @Param("from") OrderStatus from,
                              @Param("to") OrderStatus to,
                              @Param("refundStatus") RefundStatus refundStatus,
                              @Param("reason") String reason);

    /**
     * Atomic CAS flip SHIPPED→DELIVERED recording the delivered timestamp.
     * Preferred over {@code updateStatusIf} + a separate {@code updateById} so
     * {@code delivered_at} is set in the same atomic transition (no race window).
     */
    int updateStatusAndDelivered(@Param("id") Long id,
                                 @Param("from") OrderStatus from,
                                 @Param("to") OrderStatus to,
                                 @Param("deliveredAt") LocalDateTime deliveredAt);

    /**
     * Atomic CAS flip PENDING→PAID recording the paid timestamp. Mirrors
     * {@code updateStatusAndDelivered}: sets {@code paid_at} in the same atomic
     * transition as the status flip (no race window), so every sibling timestamp
     * (paid_at / shipped_at / delivered_at / completed_at) is set on its transition.
     */
    int updateStatusAndPaid(@Param("id") Long id,
                             @Param("from") OrderStatus from,
                             @Param("to") OrderStatus to,
                             @Param("paidAt") LocalDateTime paidAt);

    /**
     * Atomic CAS flip DELIVERED→COMPLETED recording the completed timestamp.
     * Used by the guest's {@code confirmReceipt} (the guest picked up the order
     * at the depot). Sets {@code completed_at} in the same atomic transition as
     * the status flip — no race window, no separate {@code updateById}.
     */
    int updateStatusAndCompleted(@Param("id") Long id,
                                  @Param("from") OrderStatus from,
                                  @Param("to") OrderStatus to,
                                  @Param("completedAt") LocalDateTime completedAt);
}
