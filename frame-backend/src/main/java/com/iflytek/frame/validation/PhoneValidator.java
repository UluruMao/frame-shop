package com.iflytek.frame.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link Phone} — an 11-digit numeric string ({@code \d{11}}).
 * {@code null} passes (presence is delegated to {@code @NotBlank}).
 */
public class PhoneValidator implements ConstraintValidator<Phone, String> {

    private static final String PHONE_REGEX = "\\d{11}";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.matches(PHONE_REGEX);
    }
}
