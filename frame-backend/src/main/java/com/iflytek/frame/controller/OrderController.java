package com.iflytek.frame.controller;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.RefundRequestRequest;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.PaymentService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Order", description = "Order status, my orders, pay, cancel, refund, confirm receipt")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final PaymentService paymentService;

    @Operation(summary = "Get my order by id")
    @GetMapping("/{id}")
    public Result<Order> get(@PathVariable Long id) {
        return Result.success(orderService.getById(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "My orders (optionally filtered by status)")
    @GetMapping("/me")
    public Result<PageResult<Order>> myOrders(@Valid @ParameterObject PageQuery query,
                                              @RequestParam(required = false) OrderStatus status) {
        return Result.success(orderService.myOrders(SecurityUtils.currentUserId(), query, status));
    }

    @Operation(summary = "Cancel a pending order")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id, SecurityUtils.currentUserId());
        return Result.success();
    }

    @Operation(summary = "Cancel a paid order (with refund)")
    @PostMapping("/{id}/cancel-paid")
    public Result<Void> cancelPaid(@PathVariable Long id) {
        orderService.cancelPaid(id, SecurityUtils.currentUserId());
        return Result.success();
    }

    @Operation(summary = "Request a refund on a shipped order")
    @PostMapping("/{id}/refund-request")
    public Result<Void> refundRequest(@PathVariable Long id, @RequestBody RefundRequestRequest body) {
        orderService.requestRefund(id, SecurityUtils.currentUserId(), body.reason());
        return Result.success();
    }

    @Operation(summary = "Confirm receipt of a shipped order")
    @PostMapping("/{id}/confirm-receipt")
    public Result<Void> confirmReceipt(@PathVariable Long id) {
        orderService.confirmReceipt(id, SecurityUtils.currentUserId());
        return Result.success();
    }

    @Operation(summary = "List line items for an order")
    @GetMapping("/{id}/items")
    public Result<List<OrderItem>> items(@PathVariable Long id) {
        return Result.success(orderService.listItems(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Pay a pending order (mock payment)")
    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        return Result.success(paymentService.pay(id, SecurityUtils.currentUserId()));
    }
}
