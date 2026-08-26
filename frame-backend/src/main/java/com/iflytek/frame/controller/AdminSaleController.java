package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.service.StockGateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin endpoints for flash-sale lifecycle management.
 *
 * <p>Protected by the {@code hasRole("ADMIN")} rule in {@code SecurityConfig}:
 * {@code POST /admin/sales/{productId}}.
 */
@Tag(name = "Admin", description = "Admin sale management")
@RestController
@RequestMapping("/admin/sales")
@RequiredArgsConstructor
public class AdminSaleController {

    private final StockGateService stockGateService;

    /**
     * Open a flash sale: load the product's current DB stock into Redis ({@code stock:{id}})
     * so the Lua gate can serve claims.
     */
    @Operation(summary = "Open a flash sale (load stock into Redis)")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{productId}")
    public Result<Void> openSale(@PathVariable Long productId) {
        stockGateService.loadStock(productId);
        return Result.success();
    }
}
