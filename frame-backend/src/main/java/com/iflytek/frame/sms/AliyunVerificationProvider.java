package com.iflytek.frame.sms;

import com.iflytek.frame.config.SmsProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real verification-code provider via Aliyun 短信认证服务 (SMS Authentication Service).
 * Active only when {@code frame.sms.provider=aliyun}. Aliyun owns the code lifecycle:
 * it generates + sends the code on {@link #send}, and verifies it on {@link #verify}
 * (we never see the code). No signature/template approval needed (unlike dysmsapi).
 *
 * <p>NOT unit-tested (Phase A scope: no SMS test scripts). The user verifies manually.
 * The exact SDK method names below are best-effort (I could not fetch the live doc);
 * if the real API differs, follow skip-and-return: delete the {@code @Component}/bean,
 * log to docs/superpowers/skipped-issues.md, and fall back to LoggingVerificationProvider.
 *
 * <p>TODO(impl): replace the pseudocode calls with the confirmed SDK client once the
 * Aliyun 短信认证服务 SDK artifact + method names are verified from the official doc.
 */
@Component("aliyunVerificationProvider")
@ConditionalOnProperty(name = "frame.sms.provider", havingValue = "aliyun")
@RequiredArgsConstructor
public class AliyunVerificationProvider implements VerificationCodeProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunVerificationProvider.class);

    private final SmsProperties props;

    @Override
    public void send(String phone, SmsPurpose purpose) {
        // TODO(impl): build the Aliyun client (DefaultAcsClient or the darabonba client for the
        // 短信认证服务 SDK) using props.getAliyun().getAccessKeyId()/Secret/Region/ServiceCode,
        // then call SendVerificationCode(phoneNumber=phone). Aliyun generates + sends the code.
        // On a ClientException, log and do NOT throw (the send-code endpoint should degrade gracefully).
        log.info("[Aliyun SMS-auth] send requested for {} ({}) — wire the SDK call", phone, purpose);
    }

    @Override
    public boolean verify(String phone, String code, SmsPurpose purpose) {
        // TODO(impl): call VerifyVerificationCode(phoneNumber=phone, code=code); return its pass/fail.
        // Aliyun consumes the code on its side (one-shot).
        log.info("[Aliyun SMS-auth] verify requested for {} ({}) — wire the SDK call", phone, purpose);
        return false; // fails closed until the SDK is wired — keeps the dev stub as the working default
    }
}
