package com.iflytek.frame.exception;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.List;

/**
 * Global exception handler. Every exception that escapes a controller is translated
 * here into a {@link Result} envelope so the frontend always sees a consistent shape.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@link BusinessException} → 400, business code + message</li>
 *   <li>Bean validation ({@code @Valid}) → 400, field-level details</li>
 *   <li>Auth / access → 401 / 403</li>
 *   <li>Not found → 404</li>
 *   <li>Everything else → 500 (message logged, generic text returned)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("Business error on {}: [{}] {}", req.getRequestURI(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    // --- validation: @Valid on @RequestBody ---

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<List<String>>> handleNotValid(MethodArgumentNotValidException ex,
                                                               HttpServletRequest req) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultIfBlank(fe.getDefaultMessage(), "invalid"))
                .toList();
        log.warn("Validation failed on {}: {}", req.getRequestURI(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_INVALID, "parameter validation failed", errors));
    }

    // --- validation: @Validated on @RequestParam / @PathVariable ---

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<List<String>>> handleConstraintViolation(ConstraintViolationException ex,
                                                                          HttpServletRequest req) {
        List<String> errors = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList();
        log.warn("Constraint violation on {}: {}", req.getRequestURI(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_INVALID, "parameter validation failed", errors));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<List<String>>> handleBind(BindException ex, HttpServletRequest req) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + defaultIfBlank(fe.getDefaultMessage(), "invalid"))
                .toList();
        log.warn("Bind failed on {}: {}", req.getRequestURI(), errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_INVALID, "parameter validation failed", errors));
    }

    // --- common 4xx ---

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_INVALID, "missing parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ResultCode.PARAM_INVALID, "invalid parameter type: " + ex.getName()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Result<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ResultCode.NOT_FOUND, "no handler for " + ex.getHttpMethod() + " " + ex.getRequestURL()));
    }

    // --- security ---

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(ResultCode.UNAUTHORIZED, ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Result.fail(ResultCode.FORBIDDEN, ex.getMessage()));
    }

    // --- fallback ---

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {}: ", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ResultCode.INTERNAL_ERROR, "internal server error"));
    }

    private static String defaultIfBlank(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    // FieldError helper import alias (keeps the lambda tidy)
    @SuppressWarnings("unused")
    private static String fieldMessage(FieldError fe) {
        return defaultIfBlank(fe.getDefaultMessage(), "invalid");
    }
}
