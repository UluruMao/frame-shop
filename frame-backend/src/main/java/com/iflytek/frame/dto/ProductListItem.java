package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Read projection for {@code GET /products}: base product fields plus the two
 * aggregated sort columns (sold, avgRating) computed by the custom XML query.
 * Not an entity — carries derived columns the Product entity doesn't have.
 */
@Schema(description = "Product list item with sales + rating aggregates")
@Data
public class ProductListItem {
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
