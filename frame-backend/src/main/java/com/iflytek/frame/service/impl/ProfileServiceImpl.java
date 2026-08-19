package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.UpdateProfileRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.ProfileService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.config.SmsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserService userService;
    private final SmsProperties smsProperties;

    @Value("${frame.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    public void updateProfile(Long userId, String currentUsername, UpdateProfileRequest req) {
        User u = userService.findByUsername(currentUsername);
        if (u == null || !u.getId().equals(userId)) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (req.getNickname() != null && UserServiceImpl.containsBannedWordStatic(req.getNickname())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "nickname contains a banned word");
        }
        if (req.getSignature() != null && UserServiceImpl.containsBannedWordStatic(req.getSignature())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "signature contains a banned word");
        }
        userService.updateProfileFields(userId,
                req.getNickname() != null ? req.getNickname() : u.getNickname(),
                req.getSignature() != null ? req.getSignature() : u.getSignature());
    }

    @Override
    public String uploadAvatar(Long userId, MultipartFile file) {
        try {
            Path dir = Path.of(uploadDir, "avatars");
            Files.createDirectories(dir);
            String ext = "";
            String name = file.getOriginalFilename();
            if (name != null && name.contains(".")) ext = name.substring(name.lastIndexOf('.'));
            String filename = "avatar_" + userId + "_" + UUID.randomUUID() + ext;
            Files.copy(file.getInputStream(), dir.resolve(filename));
            String url = "/uploads/avatars/" + filename;
            // persist the avatar url on the user
            User u = new User(); u.setId(userId); u.setAvatarUrl(url);
            userService.updateAvatar(userId, url);
            return url;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "avatar upload failed");
        }
    }
}
