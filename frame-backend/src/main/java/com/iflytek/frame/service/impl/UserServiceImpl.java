package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.iflytek.frame.common.Role;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.UserService;
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

    @Override
    public User register(RegisterRequest request) {
        User existing = findByUsername(request.getUsername());
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "username already taken");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.CONFLICT, "username already taken");
        }
        return user;
    }

    @Override
    public User findByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
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
}
