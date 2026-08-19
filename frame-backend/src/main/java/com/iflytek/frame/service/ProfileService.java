package com.iflytek.frame.service;

import com.iflytek.frame.dto.UpdateProfileRequest;
import org.springframework.web.multipart.MultipartFile;

public interface ProfileService {
    void updateProfile(Long userId, String currentUsername, UpdateProfileRequest req);
    String uploadAvatar(Long userId, MultipartFile file);
}
