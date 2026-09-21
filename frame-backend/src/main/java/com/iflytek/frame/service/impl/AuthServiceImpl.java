package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.service.AuthService;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.LoginAttemptService;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final LoginAttemptService loginAttemptService;
    private final CoinService coinService;

    @Override
    public LoginResponse login(LoginRequest request) {
        String id = request.getUsername();
        User user = id.matches("\\d{11}") ? userService.findByPhone(id) : userService.findByUsername(id);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "账号或密码错误");
        }
        if ("LOCKED".equals(user.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "账号已锁定，请使用找回密码功能");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            if (user.getId() != null) loginAttemptService.recordFailure(user.getId());
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "账号或密码错误");
        }
        loginAttemptService.recordSuccess(user.getId());
        try {
            coinService.awardDailyLogin(user.getId());
        } catch (Exception e) {
            log.warn("awardDailyLogin({}) failed: {}", user.getId(), e.getMessage());
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, "Bearer", jwtUtil.getExpireSeconds(),
                user.getId(), user.getUsername(), user.getRole().name());
    }
}
