package com.iflytek.frame.controller;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ShipRequest;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.service.AdminOrderService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

/**
 * Admin endpoints for order lifecycle + refund adjudication.
 *
 * <p>Protected by the {@code hasRole("ADMIN")} rules in {@code SecurityConfig}:
 * {@code GET /admin/orders}, {@code GET /admin/refunds}, and the four
 * {@code POST /admin/orders|refunds/...} mutation paths.
 */
@Tag(name = "Admin", description = "Admin order management + refund adjudication")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @Operation(summary = "List all orders (optionally filtered by status)")
    @GetMapping("/orders")
    public Result<PageResult<Order>> listOrders(@Valid @ParameterObject PageQuery q,
                                                @RequestParam(required = false) OrderStatus status) {
        return Result.success(adminOrderService.listOrders(q, status));
    }

    @Operation(summary = "Ship a paid order (record tracking number)")
    @PostMapping("/orders/{id}/ship")
    public Result<Void> ship(@PathVariable Long id, @RequestBody ShipRequest body) {
        adminOrderService.ship(id, body.trackingNumber());
        return Result.success();
    }

    @Operation(summary = "Mark a shipped order as delivered")
    @PostMapping("/orders/{id}/deliver")
    public Result<Void> deliver(@PathVariable Long id) {
        adminOrderService.deliver(id);
        return Result.success();
    }

    @Operation(summary = "List orders pending refund adjudication")
    @GetMapping("/refunds")
    public Result<PageResult<Order>> refunds(@Valid @ParameterObject PageQuery q) {
        return Result.success(adminOrderService.refundRequests(q));
    }

    @Operation(summary = "Approve a refund request (recredit stock)")
    @PostMapping("/refunds/{orderId}/approve")
    public Result<Void> approveRefund(@PathVariable Long orderId) {
        adminOrderService.approveRefund(orderId);
        return Result.success();
    }

    @Operation(summary = "Deny a refund request (return to SHIPPED)")
    @PostMapping("/refunds/{orderId}/deny")
    public Result<Void> denyRefund(@PathVariable Long orderId) {
        adminOrderService.denyRefund(orderId);
        return Result.success();
    }
}
