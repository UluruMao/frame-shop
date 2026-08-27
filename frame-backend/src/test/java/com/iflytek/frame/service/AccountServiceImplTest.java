package com.iflytek.frame.service;

import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.impl.AccountServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AccountServiceImplTest {

    @Test
    void deactivateRejectsWrongPassword() {
        UserService userService = mock(UserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        User u = new User();
        u.setId(7L);
        u.setPasswordHash("hash");
        when(userService.findByUsername("alice")).thenReturn(u);
        when(encoder.matches("wrong", "hash")).thenReturn(false);

        AccountServiceImpl svc = new AccountServiceImpl(userService, encoder, chatSessionMapper);

        assertThatThrownBy(() -> svc.deactivateAccount(7L, "alice", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码不正确");
        verify(userService, never()).deactivateAccount(any());
    }

    @Test
    void deactivateAnonymizesAndClosesChatOnCorrectPassword() {
        UserService userService = mock(UserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        User u = new User();
        u.setId(7L);
        u.setPasswordHash("hash");
        when(userService.findByUsername("alice")).thenReturn(u);
        when(encoder.matches("right", "hash")).thenReturn(true);

        AccountServiceImpl svc = new AccountServiceImpl(userService, encoder, chatSessionMapper);

        svc.deactivateAccount(7L, "alice", "right");

        verify(userService).deactivateAccount(7L);
        verify(chatSessionMapper).update(any()); // closes OPEN sessions to CLOSED (see impl)
    }
}
