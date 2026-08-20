package com.iflytek.frame.service;

import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;

import java.util.List;

public interface ReviewService {

    /** Create or upsert a review for one order line item (UC010). Triggers COMPLETED + coin seam
     *  when this is the order's last item's first review. */
    ReviewVO create(ReviewCreateRequest req, Long userId);

    /** Edit an existing review (any field). Does not re-trigger COMPLETED/coin. */
    ReviewVO update(Long reviewId, ReviewUpdateRequest req, Long userId);

    /** All reviews for an order (ownership-checked), with product-name snapshots. */
    List<ReviewVO> listByOrder(Long orderId, Long userId);
}
