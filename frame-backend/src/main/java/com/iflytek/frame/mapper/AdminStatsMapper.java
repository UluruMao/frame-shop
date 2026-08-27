package com.iflytek.frame.mapper;

import com.iflytek.frame.dto.DashboardStatsVO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

/** Aggregate queries backing the admin dashboard (time-windowed). */
@Mapper
public interface AdminStatsMapper {

    /**
     * The five tile metrics over the [from, to) window. The three chart datasets
     * (unitsByProduct, revenueTrend, salesShareByProduct) are returned as NULL
     * placeholders here and loaded separately by the service (mirroring monthlyRevenue).
     *
     * @param from       inclusive lower bound (paid_at / create_time >= from)
     * @param to          exclusive upper bound (paid_at / create_time < to)
     * @param bucketExpr  whitelisted SQL DATE_FORMAT expression — see {@link
     *                    com.iflytek.frame.service.impl.AdminStatsServiceImpl#BUCKET_DAY}
     *                    (server-selected constant, NEVER client input; passed via ${}
     *                    so it must not be user-supplied)
     */
    DashboardStatsVO dashboardStats(LocalDateTime from, LocalDateTime to, String bucketExpr);

    /** SUM(order_item.quantity) per product in the window (units sold, not revenue). */
    List<DashboardStatsVO.ProductUnits> unitsByProduct(LocalDateTime from, LocalDateTime to);

    /**
     * Paid revenue grouped by time bucket in the window.
     * @param bucketExpr  whitelisted DATE_FORMAT expression (server constant — see dashboardStats)
     */
    List<DashboardStatsVO.TrendPoint> revenueTrend(LocalDateTime from, LocalDateTime to, String bucketExpr);

    /** SUM(order_item.quantity) per product in the window (share-by-units). */
    List<DashboardStatsVO.ProductShare> salesShareByProduct(LocalDateTime from, LocalDateTime to);
}
