package com.iflytek.frame.service.impl;

import com.iflytek.frame.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginAttemptServiceImplTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private UserService userService;
    private LoginAttemptServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        userService = mock(UserService.class);
        service = new LoginAttemptServiceImpl(redis, userService);
    }

    @Test
    void recordFailure_incrementsAnd_setsTtl() {
        when(ops.increment("login:fail:1")).thenReturn(3L);
        service.recordFailure(1L);
        verify(ops).increment("login:fail:1");
        verify(redis).expire("login:fail:1", Duration.ofSeconds(1800));
        verify(userService, never()).updateStatus(anyLong(), anyString(), anyInt());
    }

    @Test
    void recordFailure_atTen_locksAccount() {
        when(ops.increment("login:fail:1")).thenReturn(10L);
        service.recordFailure(1L);
        verify(userService).updateStatus(1L, "LOCKED", 10);
    }

    @Test
    void recordSuccess_clearsCounterAndAccount() {
        service.recordSuccess(1L);
        verify(redis).delete("login:fail:1");
        verify(userService).clearFailedAttempts(1L);
    }

    @Test
    void unlock_deletesCounterAndSetsActive() {
        service.unlock(1L);
        verify(redis).delete("login:fail:1");
        verify(userService).updateStatus(1L, "ACTIVE", 0);
    }

    @Test
    void isLocked_readsCounterAtOrAboveTen() {
        when(ops.get("login:fail:1")).thenReturn("10");
        org.assertj.core.api.Assertions.assertThat(service.isLocked(1L)).isTrue();
        when(ops.get("login:fail:1")).thenReturn("9");
        org.assertj.core.api.Assertions.assertThat(service.isLocked(1L)).isFalse();
    }
}
