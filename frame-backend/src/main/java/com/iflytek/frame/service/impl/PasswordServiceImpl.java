package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.LoginAttemptService;
import com.iflytek.frame.service.PasswordService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import com.iflytek.frame.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordServiceImpl implements PasswordService {

    private final UserService userService;
    private final VerificationCodeService codeService;
    private final LoginAttemptService loginAttempts;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userService.findByUsername(SecurityUtils.currentUsername());
        if (user == null || !user.getId().equals(userId)) {
            // fallback: find by id path not in UserService; use the username route consistently
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "old password incorrect");
        }
        if (!UserServiceImpl.validPassword(newPassword)) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "new password must be ≥8 chars with ≥2 of {upper, lower, special}");
        }
        userService.updatePasswordByPhone(user.getPhone(), passwordEncoder.encode(newPassword));
    }

    @Override
    public void resetByPhone(String phone, String code, String newPassword) {
        if (!UserServiceImpl.validPassword(newPassword)) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "password must be ≥8 chars with ≥2 of {upper, lower, special}");
        }
        if (!codeService.verifyAndConsume(phone, code, SmsPurpose.FORGOT_PASSWORD)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "verification code invalid or expired");
        }
        userService.updatePasswordByPhone(phone, passwordEncoder.encode(newPassword));
        User u = userService.findByPhone(phone);
        if (u != null) loginAttempts.unlock(u.getId());
    }
}
