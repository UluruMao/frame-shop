package com.iflytek.frame.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Review;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ReviewMapper;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.ReviewService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    private final ReviewMapper reviewMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderService orderService;
    private final CoinService coinService;
    /**
     * Instantiated directly (not injected) because Spring Boot 4 auto-configures a Jackson 3
     * {@code tools.jackson.databind.ObjectMapper} bean, which is not assignment-compatible with
     * the Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper} this class uses (brought
     * in transitively by jjwt-jackson). Mirrors {@code OperationLogAspect} / {@code SecurityConfig}.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public ReviewVO create(ReviewCreateRequest req, Long userId) {
        // 1-2. ownership-before-state (orderService.getById throws NOT_FOUND on mismatch).
        Order order = orderService.getById(req.getOrderId(), userId);
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(ResultCode.CONFLICT, "cannot review: order not delivered");
        }
        // 3. productId must be in the order's items.
        List<OrderItem> items = itemsOf(order.getId());
        Set<Long> orderProductIds = items.stream().map(OrderItem::getProductId).collect(Collectors.toSet());
        if (!orderProductIds.contains(req.getProductId())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "product not in this order");
        }

        // 4. upsert
        Review existing = reviewMapper.selectByOrderAndProduct(order.getId(), req.getProductId());
        if (existing != null) {
            applyCreateToExisting(existing, req);
            reviewMapper.updateById(existing);
            return toVO(existing, items);
        }

        // 5. first-review-that-completes-all-items → COMPLETED + coin seam.
        // Fetch the pre-insert reviewed-pid set once. `nowFullyReviewed` accounts for the review
        // we are about to insert (its productId) in-memory rather than re-querying — the insert
        // has not been flushed to a readable state at this point in the same tx, and the
        // just-reviewed pid is by definition now reviewed.
        Set<Long> reviewedBefore = reviewedProductIds(order.getId());
        boolean wasFullyReviewed = reviewedBefore.containsAll(orderProductIds);
        Review r = fromCreate(req, order.getUserId());
        reviewMapper.insert(r);
        Set<Long> reviewedAfter = new HashSet<>(reviewedBefore);
        reviewedAfter.add(req.getProductId());
        boolean nowFullyReviewed = reviewedAfter.containsAll(orderProductIds);
        if (!wasFullyReviewed && nowFullyReviewed) {
            try {
                orderService.completeOrder(order.getId());   // CAS idempotent; no-op if already COMPLETED
            } catch (Exception e) {
                log.warn("completeOrder({}) failed after review: {}", order.getId(), e.getMessage());
            }
            try {
                coinService.awardReviewReward(order.getId(), userId);
            } catch (Exception e) {
                log.warn("coinService.awardReviewReward({}) failed: {}", order.getId(), e.getMessage());
            }
        }
        return toVO(r, items);
    }

    @Override
    @Transactional
    public ReviewVO update(Long reviewId, ReviewUpdateRequest req, Long userId) {
        Review r = reviewMapper.selectById(reviewId);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "review not found");
        }
        if (req.getQualityStars() != null) r.setQualityStars(req.getQualityStars());
        if (req.getLogisticsStars() != null) r.setLogisticsStars(req.getLogisticsStars());
        if (req.getServiceStars() != null) r.setServiceStars(req.getServiceStars());
        if (req.getContent() != null) r.setContent(req.getContent());
        if (req.getImageUrls() != null) r.setImages(toImagesJson(req.getImageUrls()));
        if (req.getIsAnonymous() != null) r.setIsAnonymous(req.getIsAnonymous());
        r.setEditedAt(LocalDateTime.now());
        reviewMapper.updateById(r);
        return toVO(r, itemsOf(r.getOrderId()));
    }

    @Override
    public List<ReviewVO> listByOrder(Long orderId, Long userId) {
        orderService.getById(orderId, userId); // ownership check (NOT_FOUND on mismatch)
        List<OrderItem> items = itemsOf(orderId);
        return reviewMapper.selectByOrderId(orderId).stream()
                .map(r -> toVO(r, items))
                .toList();
    }

    // --- helpers ---

    private List<OrderItem> itemsOf(Long orderId) {
        return orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
    }

    /** The distinct productIds already reviewed for an order (pre-insert snapshot). */
    private Set<Long> reviewedProductIds(Long orderId) {
        return reviewMapper.selectByOrderId(orderId).stream()
                .map(Review::getProductId).collect(Collectors.toSet());
    }

    private void applyCreateToExisting(Review existing, ReviewCreateRequest req) {
        existing.setQualityStars(req.getQualityStars());
        existing.setLogisticsStars(req.getLogisticsStars());
        existing.setServiceStars(req.getServiceStars());
        if (req.getContent() != null) existing.setContent(req.getContent());
        existing.setImages(toImagesJson(req.getImageUrls()));
        if (req.getIsAnonymous() != null) existing.setIsAnonymous(req.getIsAnonymous());
        existing.setEditedAt(LocalDateTime.now());
    }

    private Review fromCreate(ReviewCreateRequest req, Long userId) {
        Review r = new Review();
        r.setOrderId(req.getOrderId());
        r.setUserId(userId);
        r.setProductId(req.getProductId());
        r.setQualityStars(req.getQualityStars());
        r.setLogisticsStars(req.getLogisticsStars());
        r.setServiceStars(req.getServiceStars());
        r.setContent(req.getContent());
        r.setImages(toImagesJson(req.getImageUrls()));
        r.setIsAnonymous(Boolean.TRUE.equals(req.getIsAnonymous()));
        return r;
    }

    private String toImagesJson(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(imageUrls);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "image serialization failed");
        }
    }

    private List<String> fromImagesJson(String images) {
        if (images == null || images.isBlank()) return List.of();
        try {
            return objectMapper.readValue(images, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // Degrade gracefully (no images) but leave a diagnostic trail — a corrupted
            // images column should not silently read as empty with no signal.
            log.warn("failed to parse review images json ({}): {}", images, e.getMessage());
            return List.of();
        }
    }

    private ReviewVO toVO(Review r, List<OrderItem> items) {
        ReviewVO vo = new ReviewVO();
        vo.setId(r.getId());
        vo.setOrderId(r.getOrderId());
        vo.setProductId(r.getProductId());
        vo.setProductName(items.stream()
                .filter(it -> it.getProductId().equals(r.getProductId()))
                .map(OrderItem::getName).findFirst().orElse(null));
        vo.setQualityStars(r.getQualityStars());
        vo.setLogisticsStars(r.getLogisticsStars());
        vo.setServiceStars(r.getServiceStars());
        vo.setContent(r.getContent());
        vo.setImageUrls(fromImagesJson(r.getImages()));
        vo.setIsAnonymous(r.getIsAnonymous());
        vo.setEditedAt(r.getEditedAt());
        vo.setCreateTime(r.getCreateTime());
        return vo;
    }
}
