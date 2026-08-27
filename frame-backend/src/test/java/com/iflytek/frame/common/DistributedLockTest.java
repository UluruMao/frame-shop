package com.iflytek.frame.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link DistributedLock}.
 *
 * <p>The brief's verbatim test stubbed {@code redis.opsForValue().setIfAbsent(...)} directly,
 * but Mockito returns {@code null} from the unstubbed {@code redis.opsForValue()} call, so the
 * chained stub NPEs before reaching the assertion. Every other test in this repo that mocks
 * {@code StringRedisTemplate} (e.g. {@code StockGateServiceImplTest}) stubs
 * {@code when(redis.opsForValue()).thenReturn(valueOps)} first — that is the established
 * pattern here, so {@code setIfAbsent} is stubbed on the {@code ValueOperations} mock rather
 * than directly on {@code redis}.
 */
class DistributedLockTest {

    @Test
    void tryLockSetsNxExReturnsTrueWhenAcquired() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        // SET key owner NX EX ttl → "OK"
        when(valueOps.setIfAbsent(eq("shedlock:t"), eq("owner1"), any(Duration.class)))
                .thenReturn(true);
        DistributedLock lock = new DistributedLock(redis);
        assertThat(lock.tryLock("shedlock:t", "owner1", Duration.ofSeconds(30))).isTrue();
    }

    @Test
    void tryLockReturnsFalseWhenAlreadyHeld() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        DistributedLock lock = new DistributedLock(redis);
        assertThat(lock.tryLock("shedlock:t", "owner1", Duration.ofSeconds(30))).isFalse();
    }
}
