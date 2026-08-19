package com.iflytek.frame.sms;

/**
 * Strategy for the SMS verification-code lifecycle. Two impls:
 * {@link LoggingVerificationProvider} (dev/default — owns a minimal lifecycle in
 * Redis so flows work with no provider configured) and {@code AliyunVerificationProvider}
 * (Task 3 — delegates both send AND verify to Aliyun 短信认证服务, which owns the lifecycle).
 *
 * <p>Per the Phase A scope: NO test asserts physical SMS delivery — the user verifies
 * the real Aliyun provider manually. Only the dev stub's send→verify symmetry is tested.
 */
public interface VerificationCodeProvider {
    /** Send a code to the phone for this purpose. Throws on cooldown/rate-limit. */
    void send(String phone, SmsPurpose purpose);

    /** Verify the code. Returns true on match (and consumes it). Never reveals which side was wrong. */
    boolean verify(String phone, String code, SmsPurpose purpose);
}
