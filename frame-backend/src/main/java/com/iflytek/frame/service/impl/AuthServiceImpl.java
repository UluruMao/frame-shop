package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.service.AuthService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Stub login. Validates against hardcoded demo credentials so the token flow can be
 * verified end-to-end without a user table. Swap {@code DEMO_*} for a real
 * {@code user} table + stored BCrypt hash when business code is added.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    /** Cached BCrypt hash of the demo password, computed once at startup. */
    private String demoPasswordHash;

    @PostConstruct
    void init() {
        this.demoPasswordHash = passwordEncoder.encode(DEMO_PASSWORD);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        if (!DEMO_USERNAME.equals(request.getUsername())) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (!passwordEncoder.matches(request.getPassword(), demoPasswordHash)) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "bad credentials");
        }
        String token = jwtUtil.generateToken(DEMO_USER_ID, DEMO_USERNAME);
        return new LoginResponse(token, "Bearer", jwtUtil.getExpireSeconds(), DEMO_USER_ID, DEMO_USERNAME);
    }
}
