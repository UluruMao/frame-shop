package com.iflytek.frame.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CORS config bound from {@code frame.cors.*} in application.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "frame.cors")
public class CorsProperties {

    /** Comma-separated allowed origins. */
    private String allowedOrigins = "http://localhost:5173";

    /** Comma-separated allowed methods. */
    private String allowedMethods = "GET,POST,PUT,DELETE,OPTIONS,PATCH";

    private boolean allowCredentials = true;
}
