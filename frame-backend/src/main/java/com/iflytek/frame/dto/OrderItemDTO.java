package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "A checkout line item")
public class OrderItemDTO {
    @NotNull(message = "productId required")
    private Long productId;
    @NotNull(message = "quantity required")
    @Min(value = 1, message = "quantity >= 1")
    @Max(value = 9999, message = "quantity <= 9999")
    private Integer quantity;
}
