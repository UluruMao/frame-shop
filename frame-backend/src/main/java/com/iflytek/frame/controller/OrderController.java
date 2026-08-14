package com.iflytek.frame.controller;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.entity.Order;
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

@Tag(name = "Order", description = "Order status, my orders, pay, cancel")
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

    @Operation(summary = "My orders")
    @GetMapping("/me")
    public Result<PageResult<Order>> myOrders(@Valid @ParameterObject PageQuery query) {
        return Result.success(orderService.myOrders(SecurityUtils.currentUserId(), query));
    }

    @Operation(summary = "Cancel a pending order")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        orderService.cancel(id, SecurityUtils.currentUserId());
        return Result.success();
    }

    @Operation(summary = "Pay a pending order (mock payment)")
    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        return Result.success(paymentService.pay(id, SecurityUtils.currentUserId()));
    }
}
