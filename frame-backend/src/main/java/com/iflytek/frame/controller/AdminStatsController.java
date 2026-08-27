package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.DashboardStatsVO;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.AdminStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

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

    @Operation(summary = "Dashboard aggregate stats (revenue/orders/products/refunds/rating + 3 chart datasets, time-windowed)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats(@RequestParam(defaultValue = "30d") String range,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if ("custom".equals(range) && (from == null || to == null)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "custom range requires from and to");
        }
        return Result.success(adminStatsService.getDashboardStats(range, from, to));
    }
}
