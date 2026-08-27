package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.AccountService;
import com.iflytek.frame.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountServiceImpl implements AccountService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final ChatSessionMapper chatSessionMapper;

    @Override
    @Transactional
    public void deactivateAccount(Long userId, String username, String password) {
        // Re-fetch by username (not by trusting the JWT payload alone) and assert id matches.
        // Uses findByUsername + inline id check rather than UserService.requireUserMatching so the
        // security check stays local to account deletion; requireUserMatching is shared with
        // PhoneRebind/Profile/Password services and stubbed independently in their tests.
        User user = userService.findByUsername(username);
        if (user == null || !userId.equals(user.getId())) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.BAD_CREDENTIALS, "密码不正确");
        }
        // Soft-delete the user PII + close chat sessions. Orders/reviews/coins are RETAINED
        // for analytics + audit (no FK constraints in the schema — cascade is app-level).
        userService.deactivateAccount(userId);
        // Close any OPEN chat sessions for this user. Uses a string-based UpdateWrapper (not
        // LambdaUpdateWrapper) so column names resolve without MyBatis-Plus's lambda entity
        // cache, which is not initialized in plain unit tests. The single-arg update(wrapper)
        // overload carries the SET clause in the wrapper itself.
        chatSessionMapper.update(
                new UpdateWrapper<ChatSession>()
                        .eq("user_id", userId)
                        .eq("status", "OPEN")
                        .set("status", "CLOSED"));
    }
}
