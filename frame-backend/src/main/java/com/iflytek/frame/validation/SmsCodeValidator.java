package com.iflytek.frame.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link SmsCode} — a 6-digit numeric string ({@code \d{6}}).
 * {@code null} passes (presence is delegated to {@code @NotBlank}).
 */
public class SmsCodeValidator implements ConstraintValidator<SmsCode, String> {

    private static final String SMS_CODE_REGEX = "\\d{6}";

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.matches(SMS_CODE_REGEX);
    }
}
