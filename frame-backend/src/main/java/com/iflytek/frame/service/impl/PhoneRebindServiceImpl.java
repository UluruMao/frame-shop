package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.service.PhoneRebindService;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * UC017 phone rebind state machine. The "old phone verified" flag is held in Redis
 * (rebind:verified:{userId}, TTL 10 min) between the verify-old and confirm steps.
 */
@Service
@RequiredArgsConstructor
public class PhoneRebindServiceImpl implements PhoneRebindService {

    private final UserService userService;
    private final VerificationCodeService codeService;
    private final PasswordEncoder encoder;
    private final StringRedisTemplate redis;

    @Override
    public void init(Long userId, String currentUsername, boolean oldPhoneUsable) {
        // No-op beyond recording intent; the frontend drives the branch. Could send the
        // old-phone code here if oldPhoneUsable=true, but the spec's flow sends on demand.
    }

    @Override
    public void verifyOld(Long userId, String currentUsername, boolean oldPhoneUsable,
                           String code, String password) {
        User u = userService.findByUsername(currentUsername);
        if (u == null || !u.getId().equals(userId)) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (oldPhoneUsable) {
            if (u.getPhone() == null || code == null
                    || !codeService.verifyAndConsume(u.getPhone(), code, SmsPurpose.REBIND_OLD)) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "old-phone code invalid");
            }
        } else {
            if (password == null || !encoder.matches(password, u.getPasswordHash())) {
                throw new BusinessException(ResultCode.BAD_CREDENTIALS, "password incorrect");
            }
        }
        redis.opsForValue().set(RedisKeys.rebindVerified(userId), "1", Duration.ofMinutes(10));
    }

    @Override
    public void confirm(Long userId, String currentUsername, String newPhone, String code) {
        User u = userService.findByUsername(currentUsername);
        if (u == null || !u.getId().equals(userId)) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (!"1".equals(redis.opsForValue().get(RedisKeys.rebindVerified(userId)))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "old phone not verified");
        }
        if (!codeService.verifyAndConsume(newPhone, code, SmsPurpose.REBIND_NEW)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "new-phone code invalid");
        }
        userService.updatePhone(userId, newPhone);
        redis.delete(RedisKeys.rebindVerified(userId));
    }
}
