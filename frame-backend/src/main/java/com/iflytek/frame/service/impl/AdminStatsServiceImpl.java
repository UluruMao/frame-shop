package com.iflytek.frame.service.impl;

import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.mapper.AdminStatsMapper;
import com.iflytek.frame.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Assembles the dashboard VO from {@link AdminStatsMapper} over a time window.
 *
 * <p>The {@code dashboardStats} mapper returns the 5 tile metrics plus NULL placeholders for
 * the 4 list components; this service stitches in the 3 chart datasets
 * ({@code unitsByProduct}, {@code revenueTrend}, {@code salesShareByProduct}) via separate
 * mapper calls (mirroring the legacy {@code monthlyRevenue} pattern, which is now kept empty
 * for back-compat — superseded by {@code revenueTrend}).
 *
 * <h3>Time window</h3>
 * The {@code range} param ({@code 1d|7d|30d|90d|365d|1095d|custom}) selects the window
 * {@code [end - range, end)} where {@code end = to != null ? to : LocalDate.now()}. The
 * upper bound is exclusive (start of {@code end+1}). For {@code custom}, the controller
 * validates {@code from}+{@code to} are present.
 *
 * <h3>Bucket expression (SQL-injection guard)</h3>
 * {@link AdminStatsMapper#revenueTrend} and {@link AdminStatsMapper#dashboardStats} use
 * {@code ${bucketExpr}} (raw substitution). This is SAFE because the value is selected here
 * from a fixed whitelist of constants ({@link #BUCKET_DAY}, {@link #BUCKET_MONTH}) keyed by
 * the range — it is NEVER the client request string. Day bucket for ≤90d windows, month
 * bucket for 365d/1095d.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private final AdminStatsMapper adminStatsMapper;

    /** Day bucket — server-whitelisted constant for {@code ${bucketExpr}} (never client input). */
    static final String BUCKET_DAY = "DATE_FORMAT(paid_at, '%Y-%m-%d')";
    /** Month bucket — server-whitelisted constant for {@code ${bucketExpr}} (never client input). */
    static final String BUCKET_MONTH = "DATE_FORMAT(paid_at, '%Y-%m')";

    @Override
    public DashboardStatsVO getDashboardStats(String range, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        // range is normalized to "30d" when null, so no null case label is needed (and Java
        // disallows combining null with string literals in one case label anyway).
        String r = range == null ? "30d" : range;
        LocalDate start = switch (r) {
            case "1d" -> end.minusDays(1);
            case "7d" -> end.minusDays(7);
            case "30d" -> end.minusDays(30);
            case "90d" -> end.minusDays(90);
            case "365d" -> end.minusDays(365);
            case "1095d" -> end.minusDays(1095);
            case "custom" -> from; // from/to required; validated by controller
            default -> end.minusDays(30);
        };
        LocalDateTime fromDt = start.atStartOfDay();
        LocalDateTime toDt = end.plusDays(1).atStartOfDay(); // exclusive upper bound
        // Whitelisted bucket expression — never the raw client input (SQL-injection guard).
        String bucket = (range != null && (range.equals("365d") || range.equals("1095d")))
                ? BUCKET_MONTH
                : BUCKET_DAY;
        DashboardStatsVO s = adminStatsMapper.dashboardStats(fromDt, toDt, bucket);
        return new DashboardStatsVO(
                s.totalRevenueCents(), s.totalOrders(), s.totalProducts(), s.refundCount(), s.avgRating(),
                List.of(), // legacy monthlyRevenue — kept empty for back-compat (superseded by revenueTrend)
                adminStatsMapper.unitsByProduct(fromDt, toDt),
                adminStatsMapper.revenueTrend(fromDt, toDt, bucket),
                adminStatsMapper.salesShareByProduct(fromDt, toDt)
        );
    }
}
