package com.iflytek.frame.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string is a 6-digit SMS verification code ({@code \d{6}}).
 * <p>
 * {@code null} is considered valid so that presence can be handled separately by
 * {@link jakarta.validation.constraints.NotBlank @NotBlank} on required fields. Combine
 * {@code @NotBlank @SmsCode} for mandatory code fields; use {@code @SmsCode} alone when
 * the code is conditionally present (e.g. alternate auth paths).
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SmsCodeValidator.class)
public @interface SmsCode {
    String message() default "invalid sms code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
