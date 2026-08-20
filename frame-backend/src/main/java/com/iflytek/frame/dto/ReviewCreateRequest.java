package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Schema(description = "Create/upsert a review for one order line item")
@Data
public class ReviewCreateRequest {
    @NotNull
    private Long orderId;
    @NotNull
    private Long productId;
    @NotNull @Min(1) @Max(5)
    private Integer qualityStars;
    @NotNull @Min(1) @Max(5)
    private Integer logisticsStars;
    @NotNull @Min(1) @Max(5)
    private Integer serviceStars;
    @Size(max = 1000)
    private String content;
    @Size(max = 9)
    private List<String> imageUrls;
    private Boolean isAnonymous;
}
