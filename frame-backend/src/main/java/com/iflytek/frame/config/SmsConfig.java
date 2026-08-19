package com.iflytek.frame.config;

import org.springframework.context.annotation.Configuration;

/**
 * Home for SMS cross-cutting wiring. The active
 * {@link com.iflytek.frame.sms.VerificationCodeProvider} is chosen by its own
 * {@code @ConditionalOn*} annotations: {@link com.iflytek.frame.sms.LoggingVerificationProvider}
 * is the {@code @ConditionalOnMissingBean} default; when
 * {@code frame.sms.provider=aliyun}, {@code AliyunVerificationProvider} (Task 3)
 * defines a bean named {@code aliyunVerificationProvider} and takes precedence.
 */
@Configuration
public class SmsConfig {
}
