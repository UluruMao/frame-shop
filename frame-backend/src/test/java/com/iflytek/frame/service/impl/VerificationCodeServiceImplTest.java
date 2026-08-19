package com.iflytek.frame.service.impl;

import com.iflytek.frame.sms.SmsPurpose;
import com.iflytek.frame.sms.VerificationCodeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VerificationCodeServiceImplTest {

    private VerificationCodeProvider provider;
    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        provider = mock(VerificationCodeProvider.class);
        service = new VerificationCodeServiceImpl(provider);
    }

    @Test
    void sendCode_delegatesToProvider() {
        service.sendCode("13800000000", SmsPurpose.REGISTER);
        verify(provider).send("13800000000", SmsPurpose.REGISTER);
    }

    @Test
    void verifyAndConsume_delegatesToProvider_andReturnsItsResult() {
        when(provider.verify("13800000000", "123456", SmsPurpose.REGISTER)).thenReturn(true);
        assertThat(service.verifyAndConsume("13800000000", "123456", SmsPurpose.REGISTER)).isTrue();
        when(provider.verify("13800000000", "bad", SmsPurpose.REGISTER)).thenReturn(false);
        assertThat(service.verifyAndConsume("13800000000", "bad", SmsPurpose.REGISTER)).isFalse();
    }
}
