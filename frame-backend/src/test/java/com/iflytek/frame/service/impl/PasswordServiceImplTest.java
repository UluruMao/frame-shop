package com.iflytek.frame.service.impl;

import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.LoginAttemptService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordServiceImplTest {

    private UserService userService;
    private VerificationCodeService codeService;
    private LoginAttemptService loginAttempts;
    private PasswordEncoder encoder;
    private PasswordServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        codeService = mock(VerificationCodeService.class);
        loginAttempts = mock(LoginAttemptService.class);
        encoder = mock(PasswordEncoder.class);
        service = new PasswordServiceImpl(userService, codeService, loginAttempts, encoder);
    }

    @Test
    void changePassword_oldMismatch_throws() {
        com.iflytek.frame.entity.User u = new com.iflytek.frame.entity.User();
        u.setId(1L);
        u.setPasswordHash("hash");
        when(userService.requireUserMatching(anyString(), eq(1L))).thenReturn(u);
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(1L, "wrong", "NewPass1!"))
                .isInstanceOf(BusinessException.class);
        verify(userService, never()).updatePasswordByPhone(anyString(), anyString());
    }

    @Test
    void resetByPhone_validCode_updatesPasswordAndUnlocks() {
        com.iflytek.frame.entity.User u = new com.iflytek.frame.entity.User();
        u.setId(7L);
        u.setPhone("13800000000");
        when(userService.findByPhone("13800000000")).thenReturn(u);
        when(codeService.verifyAndConsume("13800000000", "123456", SmsPurpose.FORGOT_PASSWORD)).thenReturn(true);
        when(encoder.encode("NewPass1!")).thenReturn("newhash");

        service.resetByPhone("13800000000", "123456", "NewPass1!");

        verify(userService).updatePasswordByPhone("13800000000", "newhash");
        verify(loginAttempts).unlock(7L);
    }

    @Test
    void resetByPhone_invalidCode_throws() {
        when(codeService.verifyAndConsume(anyString(), anyString(), any())).thenReturn(false);
        assertThatThrownBy(() -> service.resetByPhone("13800000000", "bad", "NewPass1!"))
                .isInstanceOf(BusinessException.class);
        verify(userService, never()).updatePasswordByPhone(anyString(), anyString());
    }
}
