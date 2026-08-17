package com.iflytek.frame.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit config bound from {@code frame.rate-limit.*} in application.yml.
 *
 * <p>Currently backs the per-user token bucket on {@code POST /checkout}
 * (see {@link RateLimitFilter}). Defaults are safe enough for dev; tune per
 * deployment via {@code frame.rate-limit.checkout-per-minute}.
 */
@Data
@ConfigurationProperties(prefix = "frame.rate-limit")
public class RateLimitProperties {

    /** Max {@code POST /checkout} calls per authenticated user per 60s window. */
    private int checkoutPerMinute = 10;
}
