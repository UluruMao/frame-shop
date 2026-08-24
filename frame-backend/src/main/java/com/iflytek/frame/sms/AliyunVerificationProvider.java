package com.iflytek.frame.sms;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.iflytek.frame.config.SmsProperties;
import com.iflytek.frame.sms.SmsPurpose;
import darabonba.core.client.ClientOverrideConfiguration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

/**
 * Real verification-code provider via Aliyun 短信认证服务 (the dypnsapi variant of the
 * 号码认证服务 / Dypnsapi — NOT the traditional 短信服务 Dysmsapi). Active only when
 * {@code frame.sms.provider=aliyun}.
 *
 * <p>Aliyun owns the code lifecycle: it generates + sends the code on {@link #send} (via
 * SendSmsVerifyCode) and verifies it on {@link #verify} (via CheckSmsVerifyCode). We never
 * see the code. No 短信签名/模板 approval needed — the 短信认证服务 provides system-preset
 * {@code signName} + {@code templateCode} (configured in {@code frame.sms.aliyun.*}).
 *
 * <p>Per the Phase A scope: NO test asserts physical SMS delivery — the user verifies the
 * real provider manually. The dev {@link LoggingVerificationProvider} stays the working
 * default until this is configured + verified.
 *
 * <p>SDK: {@code com.aliyun:alibabacloud-dypnsapi20170525} (the darabonba-based AsyncClient).
 * Endpoint: {@code dypnsapi.aliyuncs.com}.
 */
@Component("aliyunVerificationProvider")
@ConditionalOnProperty(name = "frame.sms.provider", havingValue = "aliyun")
@RequiredArgsConstructor
public class AliyunVerificationProvider implements VerificationCodeProvider {

    private static final Logger log = LoggerFactory.getLogger(AliyunVerificationProvider.class);

    private final SmsProperties props;

    private AsyncClient client;

    @PostConstruct
    void init() {
        SmsProperties.Aliyun cfg = props.getAliyun();
        // StaticCredentialProvider with the configured AccessKey (not the default chain).
        StaticCredentialProvider provider = StaticCredentialProvider.create(
                Credential.builder()
                        .accessKeyId(cfg.getAccessKeyId())
                        .accessKeySecret(cfg.getAccessKeySecret())
                        .build());
        client = AsyncClient.builder()
                .region(cfg.getRegion())
                .credentialsProvider(provider)
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride("dypnsapi.aliyuncs.com"))
                .build();
        log.info("[Aliyun SMS-auth] client initialized (region={}, signName={}, templateCode={})",
                cfg.getRegion(), cfg.getSignName(), cfg.getTemplateCode());
    }

    @PreDestroy
    void close() {
        if (client != null) {
            client.close();
        }
    }

    @Override
    public void send(String phone, SmsPurpose purpose) {
        SmsProperties.Aliyun cfg = props.getAliyun();
        SendSmsVerifyCodeRequest req = SendSmsVerifyCodeRequest.builder()
                .phoneNumber(phone)
                .signName(cfg.getSignName())
                .templateCode(cfg.getTemplateCode())
                .codeLength(6L)
                .validTime(300L)
                .interval(60L)
                .build();
        try {
            SendSmsVerifyCodeResponse resp = client.sendSmsVerifyCode(req).get();
            // The dypnsapi response body carries Code/Message (Model holds BizId/RequestId).
            String code = resp.getBody().getCode();
            if (!"OK".equals(code)) {
                log.warn("[Aliyun SMS-auth] send FAILED for {} ({}): code={} message={}",
                        phone, purpose, code, resp.getBody().getMessage());
            } else {
                log.info("[Aliyun SMS-auth] send OK for {} ({}) bizId={}",
                        phone, purpose, resp.getBody().getModel().getBizId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Aliyun SMS-auth] send interrupted for {} ({})", phone, purpose, e);
        } catch (ExecutionException e) {
            // Degrade gracefully: a provider failure must NOT break the send-code endpoint.
            log.error("[Aliyun SMS-auth] send ERROR for {} ({}): {}", phone, purpose, e.getCause().getMessage());
        } catch (Exception e) {
            log.error("[Aliyun SMS-auth] send ERROR for {} ({}) — degrading: {}", phone, purpose, e.getMessage(), e);
        }
    }

    @Override
    public boolean verify(String phone, String code, SmsPurpose purpose) {
        CheckSmsVerifyCodeRequest req = CheckSmsVerifyCodeRequest.builder()
                .phoneNumber(phone)
                .verifyCode(code)
                .build();
        try {
            CheckSmsVerifyCodeResponse resp = client.checkSmsVerifyCode(req).get();
            // CheckSmsVerifyCode returns Code="OK" when the code is correct (Aliyun consumes it one-shot).
            boolean pass = "OK".equals(resp.getBody().getCode());
            if (!pass) {
                log.info("[Aliyun SMS-auth] verify FAIL for {} ({}): code={} message={}",
                        phone, purpose, resp.getBody().getCode(), resp.getBody().getMessage());
            }
            return pass;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Aliyun SMS-auth] verify interrupted for {} ({})", phone, purpose, e);
            return false;
        } catch (Exception e) {
            log.error("[Aliyun SMS-auth] verify ERROR for {} ({}) — failing closed: {}",
                    phone, purpose, e.getMessage(), e);
            return false; // fail closed — never accept a code on a provider error
        }
    }
}
