package com.iflytek.frame.service;

import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;

/**
 * Auth service: login. Stateless JWT-based.
 */
public interface AuthService {

    LoginResponse login(LoginRequest request);
}
