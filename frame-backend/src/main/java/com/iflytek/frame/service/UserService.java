package com.iflytek.frame.service;

import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;

public interface UserService {
    User register(RegisterRequest request);

    /** For Spring Security UserDetailsService. */
    User findByUsername(String username);

    /** For phone login (UC002) + rebind. */
    User findByPhone(String phone);

    /** Update status (ACTIVE/LOCKED) + failed-attempt counter atomically. */
    void updateStatus(Long userId, String status, int failedAttempts);

    /** Reset the failed-attempt counter + status to ACTIVE (used on unlock / successful login). */
    void clearFailedAttempts(Long userId);

    /** Update password hash by phone (forgot-password / lockout reset). */
    void updatePasswordByPhone(String phone, String newPasswordHash);

    /** Update phone (rebind confirm). */
    void updatePhone(Long userId, String newPhone);

    /** Update nickname + signature (UC016 profile edit). */
    void updateProfileFields(Long userId, String nickname, String signature);

    /** Update avatar URL (UC016 avatar upload). */
    void updateAvatar(Long userId, String avatarUrl);

    /** Re-fetch the user by username and assert id matches, else USER_NOT_FOUND.
     *  Replaces the 4× inline {@code u == null || !u.getId().equals(userId)} dance
     *  in PhoneRebind/Profile/Password services. */
    User requireUserMatching(String username, Long userId);
}
