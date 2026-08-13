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
    @NotBlank(message = "idempotencyKey required")
    private String idempotencyKey;
}
