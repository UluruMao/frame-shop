package com.iflytek.frame.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A review, as returned to the client")
@Data
public class ReviewVO {
    private Long id;
    private Long orderId;
    private Long productId;
    /** Product name snapshot from order_item.name (for display without a join on the client). */
    private String productName;
    private Integer qualityStars;
    private Integer logisticsStars;
    private Integer serviceStars;
    private String content;
    /** Image URLs parsed from the stored JSON array. Empty list if none. */
    private List<String> imageUrls;
    private Boolean isAnonymous;
    private LocalDateTime editedAt;
    private LocalDateTime createTime;
    /** True if this review has been edited after its first submit. */
    @JsonProperty("edited")
    public boolean isEdited() { return editedAt != null; }
}
