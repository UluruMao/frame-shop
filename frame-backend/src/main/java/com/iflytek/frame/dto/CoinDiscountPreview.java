package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * The result of validating a coin-discount request at checkout (UC005/UC012).
 * Computed in the hot path; no deduction.
 */
@Schema(description = "Coin-discount preview for a checkout cart")
@Data
public class CoinDiscountPreview {
    /** False if the cart contains any flash-sale item (coins ineligible). */
    private Boolean eligible;
    /** Max coins allowed by the 50%-of-undiscounted-subtotal rule. */
    private Integer maxCoins;
    /** The user's current usable coin balance. */
    private Integer balance;
    /** min(maxCoins, balance), floored to a multiple of 100. */
    private Integer usable;
    /** Why coins are ineligible, if `eligible` is false. */
    private String reason;
}
