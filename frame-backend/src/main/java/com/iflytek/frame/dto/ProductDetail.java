package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Detail view for {@code GET /products/{id}}: full product fields plus the
 * sold + avgRating aggregates shown on the detail page header.
 */
@Schema(description = "Product detail with sales + rating aggregates")
@Data
public class ProductDetail {
    private Long id;
    private String name;
    private int priceInCents;
    private Integer stock;
    private String imageUrl;
    private String category;
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int sold;
    private double avgRating;
}
