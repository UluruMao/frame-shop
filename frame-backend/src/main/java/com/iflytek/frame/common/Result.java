package com.iflytek.frame.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * Unified API response wrapper. Every controller endpoint returns {@code Result<T>}
 * as {@code { code, message, data, timestamp }} for a consistent contract the
 * frontend Axios interceptor can rely on.
 *
 * @param <T> payload type
 */
@Schema(description = "Unified API response")
@Getter
public class Result<T> {

    @Schema(description = "Business status code (see ResultCode)", example = "200")
    private final int code;

    @Schema(description = "Human-readable message", example = "success")
    private final String message;

    @Schema(description = "Response payload")
    private final T data;

    @Schema(description = "Server timestamp (epoch millis)")
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ---- success factories ----

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    // ---- failure factories ----

    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    /** Convenience for validation errors that carry a list of field messages. */
    public static <T> Result<T> fail(ResultCode resultCode, String message, List<String> errors) {
        return new Result<>(resultCode.getCode(), message, (T) errors);
    }

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    @Override
    public String toString() {
        return "Result{" + "code=" + code + ", message='" + message + '\'' + ", data=" + data + '}';
    }
}
