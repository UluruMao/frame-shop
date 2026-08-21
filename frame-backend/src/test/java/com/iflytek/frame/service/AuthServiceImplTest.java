package com.iflytek.frame.service;

import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

    @Mock JwtUtil jwtUtil;
    @Mock PasswordEncoder passwordEncoder;
    @Mock UserService userService;
    @Mock LoginAttemptService loginAttemptService;
    @Mock CoinService coinService;
    @InjectMocks AuthServiceImpl authService;

    private User user() {
        User u = new User();
        u.setId(1L);
        u.setUsername("alice");
        u.setPasswordHash("hash");
        u.setRole(com.iflytek.frame.common.Role.USER);
        u.setStatus("ACTIVE");
        return u;
    }

    @Test
    void login_awardsDailyLogin_afterSuccess() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        when(userService.findByUsername("alice")).thenReturn(user());
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("tok");
        when(jwtUtil.getExpireSeconds()).thenReturn(3600L);

        authService.login(req);

        verify(coinService).awardDailyLogin(1L);
    }

    @Test
    void login_isolatedFromCoinFailure() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("pw");
        when(userService.findByUsername("alice")).thenReturn(user());
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("tok");
        when(jwtUtil.getExpireSeconds()).thenReturn(3600L);
        doThrow(new RuntimeException("coin down")).when(coinService).awardDailyLogin(1L);

        // login must still succeed (return a token) despite the coin subsystem throwing
        authService.login(req);
        verify(coinService).awardDailyLogin(1L);
    }
}
