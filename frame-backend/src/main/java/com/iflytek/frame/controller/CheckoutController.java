package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Checkout", description = "Flash-sale checkout")
@RestController
@RequestMapping("/checkout")
@RequiredArgsConstructor
public class CheckoutController {
    private final CheckoutService checkoutService;

    @Operation(summary = "Place an order (synchronous in V1; async in Task 5)")
    @PostMapping
    public Result<CheckoutResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
        return Result.success(checkoutService.checkout(request, SecurityUtils.currentUserId()));
    }
}
