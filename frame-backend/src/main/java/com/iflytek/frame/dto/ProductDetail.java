package com.iflytek.frame.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    /**
     * Flash-sale (discount) flag. {@code @JsonProperty("isFlashSale")} forces the JSON property name
     * to {@code isFlashSale} — without it, Jackson derives {@code flashSale} from Lombok's
     * {@code is}-prefix getter. The frontend reads {@code detail.isFlashSale} (see
     * {@code ProductDetailPage.tsx}) → would be {@code undefined} without this. Mirrors the
     * {@link Product}, {@link ProductDTO}, and {@link ProductListItem} fixes.
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
