package com.iflytek.frame.dto;

/**
 * Request body for {@code POST /admin/orders/{id}/ship} — carries the tracking number.
 */
public record ShipRequest(String trackingNumber) {
}
