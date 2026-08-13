package com.iflytek.frame.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Standardized business status codes used by {@link Result}.
 * Keep HTTP-style ranges so the frontend can branch on them sensibly.
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "success"),

    // --- 4xx: client errors ---
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "resource not found"),
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    CONFLICT(409, "conflict"),
    UNPROCESSABLE_ENTITY(422, "unprocessable entity"),
    TOO_MANY_REQUESTS(429, "too many requests"),

    // --- 5xx: server errors ---
    INTERNAL_ERROR(500, "internal server error"),
    SERVICE_UNAVAILABLE(503, "service unavailable"),

    // --- Business-specific (custom 1xxx range) ---
    BUSINESS_ERROR(1000, "business error"),
    PARAM_INVALID(1001, "invalid parameter"),
    TOKEN_INVALID(1002, "invalid token"),
    TOKEN_EXPIRED(1003, "token expired"),
    USER_NOT_FOUND(1004, "user not found"),
    BAD_CREDENTIALS(1005, "bad credentials");

    private final int code;
    private final String message;
}
