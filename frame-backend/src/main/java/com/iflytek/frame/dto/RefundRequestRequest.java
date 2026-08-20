package com.iflytek.frame.dto;

/**
 * Request body for {@code POST /orders/{id}/refund-request} — carries the buyer's refund reason.
 */
public record RefundRequestRequest(String reason) {
}
