package com.iflytek.frame.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT-related config bound from {@code frame.jwt.*} in application.yml.
 * Keep tokens stateless so any backend instance can validate them (distributed deployment).
 */
@Data
@Component
@ConfigurationProperties(prefix = "frame.jwt")
public class JwtProperties {

    /** HMAC signing secret. MUST be overridden in production (≥ 256 bits). */
    private String secret;

    /** Token lifetime in seconds. */
    private long expire = 86400;

    /** Request header carrying the token. */
    private String header = "Authorization";

    /** Token prefix in that header. */
    private String tokenPrefix = "Bearer ";
}
