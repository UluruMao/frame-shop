package com.iflytek.frame.service.impl;

import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PhoneRebindServiceImplTest {

    private UserService userService;
    private VerificationCodeService codeService;
    private PasswordEncoder encoder;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PhoneRebindServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        codeService = mock(VerificationCodeService.class);
        encoder = mock(PasswordEncoder.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new PhoneRebindServiceImpl(userService, codeService, encoder, redis);
    }

    @Test
    void verifyOld_oldPhoneUsable_wrongCode_throws() {
        User u = new User(); u.setId(1L); u.setPhone("13800000000");
        when(userService.findByUsername("bob")).thenReturn(u);
        when(codeService.verifyAndConsume("13800000000", "bad", SmsPurpose.REBIND_OLD)).thenReturn(false);
        assertThatThrownBy(() -> service.verifyOld(1L, "bob", true, "bad", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void verifyOld_oldPhoneNotUsable_wrongPassword_throws() {
        User u = new User(); u.setId(1L); u.setPasswordHash("hash");
        when(userService.findByUsername("bob")).thenReturn(u);
        when(encoder.matches("wrong", "hash")).thenReturn(false);
        assertThatThrownBy(() -> service.verifyOld(1L, "bob", false, null, "wrong"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void confirm_validCode_updatesPhone() {
        User u = new User(); u.setId(1L); u.setUsername("bob");
        when(userService.findByUsername("bob")).thenReturn(u);
        when(valueOps.get("rebind:verified:1")).thenReturn("1");
        when(codeService.verifyAndConsume("13900000000", "123456", SmsPurpose.REBIND_NEW)).thenReturn(true);
        service.confirm(1L, "bob", "13900000000", "123456");
        verify(userService).updatePhone(1L, "13900000000");
    }
}
