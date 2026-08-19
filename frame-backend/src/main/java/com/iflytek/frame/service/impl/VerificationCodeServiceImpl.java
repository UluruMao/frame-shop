package com.iflytek.frame.service.impl;

import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import com.iflytek.frame.sms.VerificationCodeProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Delegates send/verify to the active {@link VerificationCodeProvider}. No Redis logic
 * here — the provider owns the lifecycle (dev stub: own Redis; Aliyun: Aliyun's API).
 */
@Service
@RequiredArgsConstructor
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private final VerificationCodeProvider provider;

    @Override
    public void sendCode(String phone, SmsPurpose purpose) {
        provider.send(phone, purpose);
    }

    @Override
    public boolean verifyAndConsume(String phone, String code, SmsPurpose purpose) {
        return provider.verify(phone, code, purpose);
    }
}
