package com.iflytek.frame.service;

import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;

public interface UserService {
    User register(RegisterRequest request);

    /** For Spring Security UserDetailsService. */
    User findByUsername(String username);
}
