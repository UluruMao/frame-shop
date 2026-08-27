package com.iflytek.frame.service;

import com.iflytek.frame.dto.DashboardStatsVO;

import java.time.LocalDate;

/** Aggregate dashboard metrics for the admin console (time-windowed). */
public interface AdminStatsService {
    DashboardStatsVO getDashboardStats(String range, LocalDate from, LocalDate to);
}
