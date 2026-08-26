package com.iflytek.frame.mapper;

import com.iflytek.frame.dto.DashboardStatsVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** Aggregate queries backing the admin dashboard. */
@Mapper
public interface AdminStatsMapper {

    /** The five tile metrics (monthlyRevenue is loaded separately for clarity). */
    DashboardStatsVO dashboardStats();

    /** Paid revenue grouped by month (YYYY-MM) for the last 6 months. */
    List<DashboardStatsVO.MonthRevenue> monthlyRevenue();
}
