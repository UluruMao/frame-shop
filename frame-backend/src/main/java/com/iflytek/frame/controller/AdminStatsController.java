package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.service.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin dashboard aggregate metrics. Protected by hasRole("ADMIN") via both the
 * @PreAuthorize annotation and the /admin/** matcher in SecurityConfig.
 */
@Tag(name = "Admin", description = "Admin dashboard metrics")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @Operation(summary = "Dashboard aggregate stats (revenue/orders/products/refunds/rating + monthly revenue)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats() {
        return Result.success(adminStatsService.getDashboardStats());
    }
}
