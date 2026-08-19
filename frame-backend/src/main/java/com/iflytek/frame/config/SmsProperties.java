package com.iflytek.frame.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SMS config bound from {@code frame.sms.*}. The {@code provider} selects which
 * {@link com.iflytek.frame.sms.VerificationCodeProvider} impl is active:
 * {@code logging} (dev stub — generate/log/verify-against-self) or
 * {@code aliyun} (real Aliyun 短信认证服务 — Aliyun owns the code lifecycle).
 *
 * <p>Aliyun fields are empty by default; the user fills them after activating the
 * 短信认证服务 in the Aliyun console (no signature/template approval needed).
 */
@Data
@Component
@ConfigurationProperties(prefix = "frame.sms")
public class SmsProperties {
    private String provider = "logging";
    private long cooldownSeconds = 60;
    private Aliyun aliyun = new Aliyun();

    @Data
    public static class Aliyun {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String region = "cn-hangzhou";
        private String serviceCode = "";
    }
}
