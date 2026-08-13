package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "Checkout response")
public class CheckoutResponse {
    private Long orderId;
    private String status;   // "pending" (synchronous V1) / "queued" (Task 5)
}
