package com.iflytek.frame.service;

/** Tracks failed logins and locks accounts after 10 (UC018). Redis-backed. */
public interface LoginAttemptService {
    void recordFailure(Long userId);
    void recordSuccess(Long userId);
    boolean isLocked(Long userId);
    void unlock(Long userId);
}
