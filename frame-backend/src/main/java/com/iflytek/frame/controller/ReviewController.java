package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ReviewCreateRequest;
import com.iflytek.frame.dto.ReviewUpdateRequest;
import com.iflytek.frame.dto.ReviewVO;
import com.iflytek.frame.service.ReviewImageService;
import com.iflytek.frame.service.ReviewService;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Review", description = "UC010 product reviews (per order line item)")
@RestController
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewImageService reviewImageService;

    @Operation(summary = "List reviews for an order (drawer / edit view)")
    @GetMapping("/orders/{orderId}/reviews")
    public Result<List<ReviewVO>> listByOrder(@PathVariable Long orderId) {
        return Result.success(reviewService.listByOrder(orderId, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Create/upsert a review for an order line item")
    @PostMapping("/orders/{orderId}/reviews")
    public Result<ReviewVO> create(@PathVariable Long orderId, @Valid @RequestBody ReviewCreateRequest req) {
        // path orderId is authoritative; ignore a conflicting body orderId
        req.setOrderId(orderId);
        return Result.success(reviewService.create(req, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Edit an existing review")
    @PutMapping("/reviews/{reviewId}")
    public Result<ReviewVO> update(@PathVariable Long reviewId, @Valid @RequestBody ReviewUpdateRequest req) {
        return Result.success(reviewService.update(reviewId, req, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Upload a review image (jpg/png ≤20MB)")
    @PostMapping("/reviews/images")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.success(reviewImageService.upload(file));
    }
}
