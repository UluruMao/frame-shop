package com.iflytek.frame.dto;

import java.util.List;

/**
 * Aggregate dashboard metrics for the admin console (GET /admin/stats).
 *
 * @param totalRevenueCents     SUM(orders.paid_in_cents) over PAID/SHIPPED/DELIVERED/COMPLETED in window
 * @param totalOrders           COUNT(orders) created in window
 * @param totalProducts         COUNT(product)
 * @param refundCount           COUNT(orders WHERE refund_status = 'APPROVED' in window)
 * @param avgRating             AVG((quality_stars + logistics_stars + service_stars) / 3.0) in window, 0 if none
 * @param monthlyRevenue        paid revenue grouped by month (legacy/back-compat — service fills empty list; superseded by revenueTrend)
 * @param unitsByProduct        SUM(order_item.quantity) grouped by product in window (units sold, not revenue)
 * @param revenueTrend          paid revenue grouped by time bucket (day for ≤90d, month for 365d/1095d) in window
 * @param salesShareByProduct   SUM(order_item.quantity) grouped by product in window (share-by-units, same measure as unitsByProduct)
 */
public record DashboardStatsVO(
        long totalRevenueCents,
        long totalOrders,
        long totalProducts,
        long refundCount,
        double avgRating,
        List<MonthRevenue> monthlyRevenue,
        List<ProductUnits> unitsByProduct,
        List<TrendPoint> revenueTrend,
        List<ProductShare> salesShareByProduct
) {
    public record MonthRevenue(String month, long revenueCents) {}
    public record ProductUnits(Long productId, String name, long units) {}
    public record TrendPoint(String bucket, long cents) {}
    public record ProductShare(Long productId, String name, long units) {}
}
