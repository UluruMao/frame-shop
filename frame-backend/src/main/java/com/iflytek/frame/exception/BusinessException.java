package com.iflytek.frame.exception;

import com.iflytek.frame.common.ResultCode;
import lombok.Getter;

/**
 * Thrown for expected, recoverable business-rule violations (e.g. stock underflow,
 * duplicate name). Caught by {@link GlobalExceptionHandler} and mapped to a 400
 * response carrying the code + message.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.BUSINESS_ERROR.getCode();
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
