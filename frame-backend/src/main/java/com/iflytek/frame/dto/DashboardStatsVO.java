package com.iflytek.frame.dto;

import java.util.List;

/**
 * Aggregate dashboard metrics for the admin console (GET /admin/stats).
 *
 * @param totalRevenueCents SUM(orders.paid_in_cents) over PAID/SHIPPED/DELIVERED/COMPLETED
 * @param totalOrders        COUNT(orders) across all statuses
 * @param totalProducts      COUNT(product)
 * @param refundCount        COUNT(orders WHERE refund_status = 'APPROVED')
 * @param avgRating          AVG((quality_stars + logistics_stars + service_stars) / 3.0), 0 if none
 * @param monthlyRevenue     paid revenue grouped by month, last 6 months
 */
public record DashboardStatsVO(
        long totalRevenueCents,
        long totalOrders,
        long totalProducts,
        long refundCount,
        double avgRating,
        List<MonthRevenue> monthlyRevenue
) {
    public record MonthRevenue(String month, long revenueCents) {}
}
