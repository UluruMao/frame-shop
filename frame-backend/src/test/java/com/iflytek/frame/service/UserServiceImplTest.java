package com.iflytek.frame.service;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks UserServiceImpl userService;

    @Test
    void register_hashesPassword_andAssignsUserRole_andInserts() {
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$10$hashed");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        User created = userService.register(new RegisterRequest("alice", "secret123"));

        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(created.getRole()).isEqualTo(Role.USER);
        verify(userMapper).insert(any(User.class));
    }
}
