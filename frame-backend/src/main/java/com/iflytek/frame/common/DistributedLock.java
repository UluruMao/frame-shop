package com.iflytek.frame.common;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis SET NX EX leader-election lock for the @Scheduled methods (so only one of N
 * instances runs each scheduled task per tick). unlock uses a Lua compare-and-delete so a
 * slow/expired lock is never released by a different owner.
 *
 * <p>Each scheduler caller passes its own per-instance stable id as the {@code owner}
 * (see {@code INSTANCE_ID} on the schedulers). {@code tryLock} maps to
 * {@code SET key owner NX EX ttl}; {@code unlock} runs a Lua compare-and-delete that only
 * deletes the key if its value still equals {@code owner} — preventing a slow tick from
 * releasing a lock already re-acquired by another instance after TTL expiry.
 */
@Component
@RequiredArgsConstructor
public class DistributedLock {
    private final StringRedisTemplate redis;
    private static final String UNLOCK_LUA =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);

    /**
     * Acquire the lock: {@code SET key owner NX EX ttl}. Returns true only if the key was
     * absent (we became the leader for this tick); false if another instance already holds it.
     */
    public boolean tryLock(String key, String owner, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent(key, owner, ttl);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Release the lock via Lua compare-and-delete. No-op if the lock already expired (TTL)
     * and was re-acquired by another instance — the GET won't match our owner.
     */
    public void unlock(String key, String owner) {
        redis.execute(UNLOCK_SCRIPT, List.of(key), owner);
    }
}
