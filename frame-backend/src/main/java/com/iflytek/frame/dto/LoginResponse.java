package com.iflytek.frame.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Login response payload: the JWT the frontend stores and re-sends as a Bearer header.
 */
@Schema(description = "Login response")
@Data
@AllArgsConstructor
public class LoginResponse {

    @Schema(description = "Access token", example = "eyJhbGciOi...")
    private String token;

    @Schema(description = "Token type used in the Authorization header", example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until expiry", example = "86400")
    private long expiresIn;

    @Schema(description = "User id", example = "1")
    private Long userId;

    @Schema(description = "Username", example = "admin")
    private String username;
}
