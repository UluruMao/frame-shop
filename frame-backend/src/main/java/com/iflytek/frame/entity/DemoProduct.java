package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * Demo domain entity. The {@code stock} field is deliberately present so you can
 * later demonstrate DB-level conditional-update mutex (e.g. decrement
 * {@code where stock >= qty}), matching the mentor's concurrency topic.
 */
@Schema(description = "Demo product")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("demo_product")
public class DemoProduct extends BaseEntity {

    @Schema(description = "Product name")
    private String name;

    @Schema(description = "Unit price", example = "19.99")
    private BigDecimal price;

    @Schema(description = "Stock on hand", example = "100")
    private Integer stock;

    @Schema(description = "Free-text description")
    private String description;
}
