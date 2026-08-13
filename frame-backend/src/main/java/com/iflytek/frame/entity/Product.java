package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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
    private boolean isFlashSale;
    private LocalDateTime flashSaleAt;
    private String description;
}
