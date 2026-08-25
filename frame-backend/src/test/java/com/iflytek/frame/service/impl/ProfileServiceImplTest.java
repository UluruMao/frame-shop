package com.iflytek.frame.service.impl;

import com.iflytek.frame.dto.UpdateProfileRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProfileServiceImplTest {

    private UserService userService;
    private ProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        service = new ProfileServiceImpl(userService, null);
    }

    @Test
    void updateProfile_bannedWordInNickname_throws() {
        User u = new User();
        u.setId(1L);
        u.setUsername("bob");
        when(userService.requireUserMatching("bob", 1L)).thenReturn(u);

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setNickname("admin");  // banned per banned-words.txt
        req.setSignature("hello");

        assertThatThrownBy(() -> service.updateProfile(1L, "bob", req))
                .isInstanceOf(BusinessException.class);
        verify(userService, never()).updatePhone(anyLong(), anyString());
    }

    @Test
    void updateProfile_cleanFields_appliesNicknameAndSignature() {
        User u = new User();
        u.setId(1L);
        u.setUsername("bob");
        when(userService.requireUserMatching("bob", 1L)).thenReturn(u);

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setNickname("Bobby");
        req.setSignature("hi");
        service.updateProfile(1L, "bob", req);
        // nickname/signature set on the user object + persisted via updateById
        verify(userService).updateProfileFields(1L, "Bobby", "hi");
    }
}
