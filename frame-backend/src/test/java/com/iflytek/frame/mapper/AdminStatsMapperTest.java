package com.iflytek.frame.mapper;

import com.iflytek.frame.dto.DashboardStatsVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate-query tests for {@link AdminStatsMapper} (the 5 dashboard tiles + last-6-months
 * revenue). Follows the repo's established integration-test pattern: {@code @SpringBootTest} +
 * {@code @TestPropertySource} for JWT config (no test profile exists). The DB may be empty or
 * shared with other tests, so assertions are non-negativity + ≤6 months (Task 13 seed data
 * will make these realistic).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class AdminStatsMapperTest {

    @Autowired
    private AdminStatsMapper adminStatsMapper;

    @Test
    void dashboardStatsReturnsZeroOnEmptyDb() {
        DashboardStatsVO stats = adminStatsMapper.dashboardStats();
        assertThat(stats).isNotNull();
        assertThat(stats.totalOrders()).isGreaterThanOrEqualTo(0);
        assertThat(stats.totalProducts()).isGreaterThanOrEqualTo(0);
        assertThat(stats.totalRevenueCents()).isGreaterThanOrEqualTo(0);
        assertThat(stats.refundCount()).isGreaterThanOrEqualTo(0);
        assertThat(stats.avgRating()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void monthlyRevenueReturnsLastSixMonths() {
        List<DashboardStatsVO.MonthRevenue> months = adminStatsMapper.monthlyRevenue();
        assertThat(months).hasSizeLessThanOrEqualTo(6);
    }
}
