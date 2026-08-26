package com.iflytek.frame.service.impl;

import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.mapper.AdminStatsMapper;
import com.iflytek.frame.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Assembles the dashboard VO from {@link AdminStatsMapper}. The mapper returns
 * raw aggregates (totalRevenueCents, totalOrders, totalProducts, refundCount,
 * avgRating); this service stitches in the monthly-revenue list and returns the
 * combined VO. refundRate is derived client-side from refundCount/totalOrders.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private final AdminStatsMapper adminStatsMapper;

    @Override
    public DashboardStatsVO getDashboardStats() {
        DashboardStatsVO stats = adminStatsMapper.dashboardStats();
        return new DashboardStatsVO(
                stats.totalRevenueCents(),
                stats.totalOrders(),
                stats.totalProducts(),
                stats.refundCount(),
                stats.avgRating(),
                adminStatsMapper.monthlyRevenue()
        );
    }
}
