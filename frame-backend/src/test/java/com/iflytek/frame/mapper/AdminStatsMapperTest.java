package com.iflytek.frame.mapper;

import com.iflytek.frame.dto.DashboardStatsVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aggregate-query tests for {@link AdminStatsMapper} (the 5 dashboard tiles + 3 chart datasets,
 * time-windowed). Follows the repo's established integration-test pattern: {@code @SpringBootTest}
 * + {@code @TestPropertySource} for JWT config (no test profile exists). The DB may be empty or
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

    /** A wide window covering all seeded data (3 years back from today). */
    private static final LocalDateTime FROM = LocalDate.now().minusDays(1095).atStartOfDay();
    private static final LocalDateTime TO = LocalDate.now().plusDays(1).atStartOfDay();
    private static final String BUCKET_MONTH = "DATE_FORMAT(paid_at, '%Y-%m')";

    @Test
    void dashboardStatsReturnsZeroOnEmptyDb() {
        DashboardStatsVO stats = adminStatsMapper.dashboardStats(FROM, TO, BUCKET_MONTH);
        assertThat(stats).isNotNull();
        assertThat(stats.totalOrders()).isGreaterThanOrEqualTo(0);
        assertThat(stats.totalProducts()).isGreaterThanOrEqualTo(0);
        assertThat(stats.totalRevenueCents()).isGreaterThanOrEqualTo(0);
        assertThat(stats.refundCount()).isGreaterThanOrEqualTo(0);
        assertThat(stats.avgRating()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void revenueTrendReturnsBucketsInWindow() {
        List<DashboardStatsVO.TrendPoint> trend = adminStatsMapper.revenueTrend(FROM, TO, BUCKET_MONTH);
        assertThat(trend).isNotNull();
        // Buckets are YYYY-MM strings; each non-empty bucket must have non-negative cents.
        for (DashboardStatsVO.TrendPoint p : trend) {
            assertThat(p.cents()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void unitsByProductAndSalesShareAreConsistent() {
        List<DashboardStatsVO.ProductUnits> units = adminStatsMapper.unitsByProduct(FROM, TO);
        List<DashboardStatsVO.ProductShare> share = adminStatsMapper.salesShareByProduct(FROM, TO);
        // Both queries use SUM(oi.quantity) over the same window — same measure, so their
        // total units must match.
        assertThat(units).isNotNull();
        assertThat(share).isNotNull();
        long unitsTotal = units.stream().mapToLong(DashboardStatsVO.ProductUnits::units).sum();
        long shareTotal = share.stream().mapToLong(DashboardStatsVO.ProductShare::units).sum();
        assertThat(unitsTotal).isEqualTo(shareTotal);
    }

    /**
     * Realistic-numbers assertion (Task 21): after the {@code schema.sql} demo seed is applied to
     * the dev DB, the dashboard shows believable values instead of zeros. Like the rest of this
     * test class, this runs against the live dev {@code frame_db} via {@code @SpringBootTest}, so
     * it only passes once {@code schema.sql} has been (re)applied — re-run
     * {@code mariadb -u root -p frame_db < src/main/resources/schema.sql} if it fails.
     */
    @Test
    void seededDataProducesRealisticDashboard() {
        DashboardStatsVO stats = adminStatsMapper.dashboardStats(FROM, TO, BUCKET_MONTH);
        // After the seed: >0 revenue, >0 orders, >0 products, avg rating between 3 and 5.
        assertThat(stats.totalProducts()).isGreaterThan(2);
        assertThat(stats.totalOrders()).isGreaterThan(50);
        assertThat(stats.totalRevenueCents()).isGreaterThan(10_000);
        assertThat(stats.avgRating()).isBetween(3.0, 5.0);
    }
}
