package com.iflytek.frame.service;

import com.iflytek.frame.dto.LoginRequest;
import com.iflytek.frame.dto.LoginResponse;

/**
 * Auth service: login + (future) register. Stateless JWT-based.
 */
public interface AuthService {

    LoginResponse login(LoginRequest request);

    /** Demo user id used by the stub login so a JWT can be minted without a user table. */
    Long DEMO_USER_ID = 1L;
    String DEMO_USERNAME = "admin";
    String DEMO_PASSWORD = "admin123"; // plaintext comparison — replace with a user table + BCrypt in real code
}
