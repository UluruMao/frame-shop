package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * A per-item product review (UC010). One review per (order_id, product_id) — the
 * {@code uk_review_order_product} unique key. Three 1-5 star dimensions, optional
 * text + images (JSON array of /uploads/... URLs), optional anonymity, edit tracking.
 */
@Schema(description = "Product review")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("review")
public class Review extends BaseEntity {
    private Long orderId;
    private Long userId;
    private Long productId;
    private Integer qualityStars;
    private Integer logisticsStars;
    private Integer serviceStars;
    private String content;
    /** JSON array of image URLs (≤9), e.g. ["/uploads/reviews/x.jpg"]. Null if none. */
    private String images;
    private Boolean isAnonymous;
    /** Set on any edit-after-submit; null on first create. */
    private LocalDateTime editedAt;
}
