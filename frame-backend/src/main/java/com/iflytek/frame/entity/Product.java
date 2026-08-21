package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Schema(description = "Product")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class Product extends BaseEntity {
    private String name;
    @Schema(description = "Unit price in integer cents")
    private int priceInCents;
    @Schema(description = "Stock on hand")
    private Integer stock;
    private String imageUrl;
    private String category;
    /**
     * Flash-sale (discount) flag. {@code @JsonProperty("isFlashSale")} forces the JSON property name
     * to {@code isFlashSale} — without it, Jackson derives {@code flashSale} from Lombok's
     * {@code is}-prefix getter on this primitive {@code boolean} field, so GET /products would
     * return {@code {"flashSale":true}}. The frontend {@code Product} type + {@code Checkout.tsx}
     * read {@code product.isFlashSale} → would be {@code undefined} → {@code Boolean(undefined)}
     * = false → the cart's {@code hasFlash} is always false → the coins checkbox never disables
     * for flash carts (the backend 400 still gates correctness, but the UX message never shows).
     * Mirrors the {@link com.iflytek.frame.dto.ProductDTO} fix; keeps entity response + DTO request
     * both using {@code isFlashSale} consistently.
     */
    @JsonProperty("isFlashSale")
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
}
