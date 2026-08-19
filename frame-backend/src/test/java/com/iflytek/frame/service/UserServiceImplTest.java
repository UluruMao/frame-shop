package com.iflytek.frame.service;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.impl.UserServiceImpl;
import com.iflytek.frame.sms.SmsPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock VerificationCodeService verificationCodeService;
    @InjectMocks UserServiceImpl userService;

    @Test
    void register_hashesPassword_andAssignsUserRole_andInserts() {
        when(passwordEncoder.encode("Secret1!")).thenReturn("$2a$10$hashed");
        when(userMapper.insert(any(User.class))).thenReturn(1);
        when(verificationCodeService.verifyAndConsume(eq("13800000000"), eq("123456"), eq(SmsPurpose.REGISTER)))
                .thenReturn(true);

        User created = userService.register(
                new RegisterRequest("alice", "Secret1!", "13800000000", "123456", true));

        assertThat(created.getUsername()).isEqualTo("alice");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$hashed");
        assertThat(created.getRole()).isEqualTo(Role.USER);
        verify(userMapper).insert(any(User.class));
    }
}
