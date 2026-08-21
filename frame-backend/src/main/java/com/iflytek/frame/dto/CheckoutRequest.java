package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "Checkout request")
public class CheckoutRequest {
    @NotEmpty(message = "items must not be empty")
    @Valid
    private List<OrderItemDTO> items;
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    @NotBlank(message = "customerPhone required")
    private String customerPhone;
    @NotBlank(message = "paymentMethod required")
    private String paymentMethod;   // WECHAT/ALIPAY/BANK_CARD — validated in the service
    @NotBlank(message = "idempotencyKey required")
    private String idempotencyKey;
    /** UC005: if true, deduct `coinAmount` coins at checkout. */
    private Boolean useCoins;
    /** UC005: number of coins to spend (100 coins = ¥1). Validated + clamped server-side. */
    private Integer coinAmount;
}
