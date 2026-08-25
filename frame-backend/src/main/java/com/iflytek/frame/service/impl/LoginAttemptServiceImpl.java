package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.service.LoginAttemptService;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Failed-login counter in Redis ({@code login:fail:{userId}}, TTL 30 min sliding).
 * At 10 failures the account is marked LOCKED (UC018). A successful login OR a
 * password-reset-via-SMS resets the counter + status (unlock).
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptServiceImpl implements LoginAttemptService {

    static final int LOCK_THRESHOLD = 10;
    private static final Duration TTL = Duration.ofSeconds(1800);

    private final StringRedisTemplate redis;
    private final UserService userService;

    @Override
    public void recordFailure(Long userId) {
        String key = key(userId);
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, TTL);
        if (count != null && count >= LOCK_THRESHOLD) {
            userService.updateStatus(userId, "LOCKED", count.intValue());
        }
    }

    @Override
    public void recordSuccess(Long userId) {
        redis.delete(key(userId));
        userService.clearFailedAttempts(userId);
    }

    @Override
    public boolean isLocked(Long userId) {
        String v = redis.opsForValue().get(key(userId));
        if (v == null) return false;
        try {
            return Long.parseLong(v) >= LOCK_THRESHOLD;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void unlock(Long userId) {
        redis.delete(key(userId));
        userService.updateStatus(userId, "ACTIVE", 0);
    }

    private String key(Long userId) {
        return RedisKeys.loginFail(userId);
    }
}
