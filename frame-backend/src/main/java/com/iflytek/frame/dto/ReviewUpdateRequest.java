package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Schema(description = "Edit an existing review (orderId/productId are immutable)")
@Data
public class ReviewUpdateRequest {
    @Min(1) @Max(5)
    private Integer qualityStars;
    @Min(1) @Max(5)
    private Integer logisticsStars;
    @Min(1) @Max(5)
    private Integer serviceStars;
    @Size(max = 1000)
    private String content;
    @Size(max = 9)
    private List<String> imageUrls;
    private Boolean isAnonymous;
}
