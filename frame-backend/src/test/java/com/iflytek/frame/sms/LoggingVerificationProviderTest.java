package com.iflytek.frame.sms;

import com.iflytek.frame.config.SmsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoggingVerificationProviderTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoggingVerificationProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(redis.hasKey(anyString())).thenReturn(false);
        SmsProperties props = new SmsProperties();
        props.setCooldownSeconds(60);
        provider = new LoggingVerificationProvider(redis, props);
    }

    @Test
    void send_storesCode_setsCooldown() {
        provider.send("13800000000", SmsPurpose.REGISTER);
        verify(ops).set(matches("sms:code:REGISTER:13800000000"), matches("\\d{6}"), any(Duration.class));
        verify(ops).set(eq("sms:cooldown:REGISTER:13800000000"), anyString(), any(Duration.class));
    }

    @Test
    void send_withinCooldown_throws() {
        when(redis.hasKey("sms:cooldown:REGISTER:13800000000")).thenReturn(true);
        assertThatThrownBy(() -> provider.send("13800000000", SmsPurpose.REGISTER))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
    }

    @Test
    void verify_correctCode_deletesAndReturnsTrue() {
        when(ops.get("sms:code:REGISTER:13800000000")).thenReturn("123456");
        assertThat(provider.verify("13800000000", "123456", SmsPurpose.REGISTER)).isTrue();
        verify(redis).delete("sms:code:REGISTER:13800000000");
    }

    @Test
    void verify_wrongCode_returnsFalseWithoutDelete() {
        when(ops.get("sms:code:REGISTER:13800000000")).thenReturn("123456");
        assertThat(provider.verify("13800000000", "999999", SmsPurpose.REGISTER)).isFalse();
        verify(redis, never()).delete(anyString());
    }
}
