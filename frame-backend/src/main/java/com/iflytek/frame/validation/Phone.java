package com.iflytek.frame.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a string is an 11-digit phone number ({@code \d{11}}).
 * <p>
 * {@code null} is considered valid so that presence can be handled separately by
 * {@link jakarta.validation.constraints.NotBlank @NotBlank} on required fields. Combine
 * {@code @NotBlank @Phone} for mandatory phone fields.
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PhoneValidator.class)
public @interface Phone {
    String message() default "invalid phone number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
