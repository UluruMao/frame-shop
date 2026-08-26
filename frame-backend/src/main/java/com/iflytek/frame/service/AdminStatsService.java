package com.iflytek.frame.service;

import com.iflytek.frame.dto.DashboardStatsVO;

/** Aggregate dashboard metrics for the admin console. */
public interface AdminStatsService {
    DashboardStatsVO getDashboardStats();
}
