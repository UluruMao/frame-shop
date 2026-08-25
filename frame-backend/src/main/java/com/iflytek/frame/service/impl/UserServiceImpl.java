package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.frame.common.Role;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.UserService;
import com.iflytek.frame.service.VerificationCodeService;
import com.iflytek.frame.sms.SmsPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService, UserDetailsService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verificationCodeService;

    /** UC001 password rule: ≥8 chars, ≥2 of {uppercase, lowercase, special}. */
    static boolean validPassword(String pw) {
        if (pw == null || pw.length() < 8) return false;
        int cats = 0;
        if (pw.matches(".*[A-Z].*")) cats++;
        if (pw.matches(".*[a-z].*")) cats++;
        if (pw.matches(".*[^A-Za-z0-9].*")) cats++;
        return cats >= 2;
    }

    /** UC001 username rule: 4-25 chars, alphanumeric only (no specials). */
    static boolean validUsername(String name) {
        return name != null && name.length() >= 4 && name.length() <= 25 && name.matches("[A-Za-z0-9]+");
    }

    @Override
    public User register(RegisterRequest request) {
        if (!validUsername(request.getUsername())) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "username must be 4-25 chars, alphanumeric only");
        }
        if (!validPassword(request.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "password must be ≥8 chars with ≥2 of {upper, lower, special}");
        }
        if (containsBannedWordStatic(request.getUsername())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "username contains a banned word");
        }
        if (findByPhone(request.getPhone()) != null) {
            throw new BusinessException(ResultCode.CONFLICT, "phone already registered");
        }
        User existing = findByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "username already taken");
        }
        if (!verificationCodeService.verifyAndConsume(request.getPhone(), request.getCode(), SmsPurpose.REGISTER)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "verification code invalid or expired");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        user.setPhone(request.getPhone());
        user.setStatus("ACTIVE");
        user.setFailedAttempts(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "username or phone already taken");
        }
        return user;
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    @Override
    public User requireUserMatching(String username, Long userId) {
        User u = findByUsername(username);
        if (u == null || !u.getId().equals(userId)) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found");
        }
        return u;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("user not found: " + username);
        }
        String authority = "ROLE_" + user.getRole().name();
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }

    @Override
    public User findByPhone(String phone) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
    }

    @Override
    public void updateStatus(Long userId, String status, int failedAttempts) {
        User u = new User();
        u.setId(userId);
        u.setStatus(status);
        u.setFailedAttempts(failedAttempts);
        userMapper.updateById(u);
    }

    @Override
    public void clearFailedAttempts(Long userId) {
        updateStatus(userId, "ACTIVE", 0);
    }

    @Override
    public void updatePasswordByPhone(String phone, String newPasswordHash) {
        User u = findByPhone(phone);
        if (u == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND, "user not found for phone");
        }
        User upd = new User();
        upd.setId(u.getId());
        upd.setPasswordHash(newPasswordHash);
        upd.setStatus("ACTIVE");
        upd.setFailedAttempts(0);
        userMapper.updateById(upd);
    }

    @Override
    public void updatePhone(Long userId, String newPhone) {
        User upd = new User();
        upd.setId(userId);
        upd.setPhone(newPhone);
        userMapper.updateById(upd);
    }

    @Override
    public void updateProfileFields(Long userId, String nickname, String signature) {
        User u = new User();
        u.setId(userId);
        u.setNickname(nickname);
        u.setSignature(signature);
        userMapper.updateById(u);
    }

    @Override
    public void updateAvatar(Long userId, String avatarUrl) {
        User u = new User();
        u.setId(userId);
        u.setAvatarUrl(avatarUrl);
        userMapper.updateById(u);
    }

    static boolean containsBannedWordStatic(String text) {
        // Light banned-word filter loaded from classpath:banned-words.txt (one word per line).
        // Missing file → no filtering (fail open). Keep this simple — not a security boundary.
        try (var in = UserServiceImpl.class.getResourceAsStream("/banned-words.txt")) {
            if (in == null) return false;
            String lower = text.toLowerCase();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().map(String::trim).filter(s -> !s.isEmpty())
                    .anyMatch(w -> lower.contains(w.toLowerCase()));
        } catch (Exception e) {
            return false;
        }
    }
}
