package com.iflytek.frame.sms;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.config.SmsProperties;
import com.iflytek.frame.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dev/default verification-code provider: owns a minimal lifecycle in Redis so all
 * flows (register/forgot/rebind/lockout) work with no real provider configured.
 * Generates a 6-digit code, stores it (5 min TTL), enforces a 60s cooldown, logs the
 * code, and verifies against the stored value (one-shot). Active when
 * {@code frame.sms.provider=logging} (default) or when no other provider bean matches.
 *
 * <p>In dev, watch the backend console for: {@code [SMS] REGISTER -> 13800000000 code=123456}.
 */
@Component
@ConditionalOnMissingBean(name = "aliyunVerificationProvider")
@RequiredArgsConstructor
public class LoggingVerificationProvider implements VerificationCodeProvider {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationProvider.class);
    private static final long CODE_TTL = 300;

    private final StringRedisTemplate redis;
    private final SmsProperties props;

    @Override
    public void send(String phone, SmsPurpose purpose) {
        String cooldownKey = "sms:cooldown:" + purpose + ":" + phone;
        if (Boolean.TRUE.equals(redis.hasKey(cooldownKey))) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS, "please wait before requesting another code");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        redis.opsForValue().set(codeKey(purpose, phone), code, Duration.ofSeconds(CODE_TTL));
        redis.opsForValue().set(cooldownKey, "1", Duration.ofSeconds(props.getCooldownSeconds()));
        log.info("[SMS] {} -> {} code={}", purpose, phone, code);
    }

    @Override
    public boolean verify(String phone, String code, SmsPurpose purpose) {
        String key = codeKey(purpose, phone);
        String stored = redis.opsForValue().get(key);
        if (stored == null || !stored.equals(code)) {
            return false;
        }
        redis.delete(key);
        return true;
    }

    private String codeKey(SmsPurpose purpose, String phone) {
        return "sms:code:" + purpose + ":" + phone;
    }
}
