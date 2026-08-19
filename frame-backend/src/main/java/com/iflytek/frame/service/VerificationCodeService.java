package com.iflytek.frame.service;

import com.iflytek.frame.sms.SmsPurpose;

/**
 * Thin facade over the active {@link com.iflytek.frame.sms.VerificationCodeProvider}.
 * Controllers + services call this (not the provider directly) so the provider impl
 * (dev stub vs Aliyun) is swappable and mockable in tests.
 */
public interface VerificationCodeService {
    /** Send a code to the phone for this purpose. Provider enforces cooldown/rate-limit. */
    void sendCode(String phone, SmsPurpose purpose);

    /** Verify the code (one-shot). Returns false on mismatch. */
    boolean verifyAndConsume(String phone, String code, SmsPurpose purpose);
}
