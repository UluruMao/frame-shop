package com.iflytek.frame.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    /**
     * Flash-sale (discount) flag. {@code @JsonProperty("isFlashSale")} forces the JSON property name
     * to {@code isFlashSale} — without it, Jackson derives {@code flashSale} from Lombok's
     * {@code is}-prefix getter on this primitive {@code boolean} field, so GET /products returns
     * {@code {"flashSale":true}}. The frontend {@code Product} type + {@code Checkout.tsx} +
     * {@code Products.tsx} read {@code product.isFlashSale} → would be {@code undefined} →
     * {@code Boolean(undefined)}=false → the cart's {@code hasFlash} is always false → the coins
     * checkbox never disables for flash carts (the backend 400 still gates correctness, but the UX
     * message never shows). Mirrors the {@link Product} + {@link ProductDTO} fixes.
     */
    @JsonProperty("isFlashSale")
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    @Schema(description = "Flash-sale discount percent (1-99); null = no discount even if flash")
    private Integer discountPercent;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private int sold;
    private double avgRating;
}
