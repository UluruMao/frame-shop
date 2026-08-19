package com.iflytek.frame.service;

public interface PasswordService {
    /** UC015 logged-in change: verify old, set new. */
    void changePassword(Long userId, String oldPassword, String newPassword);

    /** UC015/UC018 forgot/reset: verify SMS code, set new password, unlock. */
    void resetByPhone(String phone, String code, String newPassword);
}
