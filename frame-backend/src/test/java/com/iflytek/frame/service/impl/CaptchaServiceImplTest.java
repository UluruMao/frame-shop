package com.iflytek.frame.service.impl;

import com.iflytek.frame.dto.CaptchaResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CaptchaServiceImplTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private CaptchaServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new CaptchaServiceImpl(redis);
    }

    @Test
    void generate_returnsIdAndSvg_andStoresAnswer() {
        CaptchaResponse cap = service.generate();
        assertThat(cap.getCaptchaId()).isNotBlank();
        assertThat(cap.getSvgImage()).contains("svg");
        verify(ops).set(startsWith("captcha:"), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void verify_correctAnswer_deletesAndReturnsTrue() {
        when(ops.get("captcha:abc")).thenReturn("7");
        boolean ok = service.verify("captcha:abc", "7");
        assertThat(ok).isTrue();
        verify(redis).delete("captcha:abc");
    }

    @Test
    void verify_wrongOrMissingAnswer_returnsFalseWithoutDelete() {
        when(ops.get("captcha:abc")).thenReturn("7");
        assertThat(service.verify("captcha:abc", "8")).isFalse();
        verify(redis, never()).delete(anyString());
        when(ops.get("captcha:none")).thenReturn(null);
        assertThat(service.verify("captcha:none", "7")).isFalse();
    }
}
