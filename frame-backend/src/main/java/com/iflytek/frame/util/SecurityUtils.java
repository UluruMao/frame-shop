package com.iflytek.frame.util;

import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.security.SecurityUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Reads the authenticated user from the {@code SecurityContextHolder}.
 * Services that need "who is making this request" use {@link #currentUser()}.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static SecurityUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SecurityUser user)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "no authenticated user");
        }
        return user;
    }

    public static Long currentUserId() {
        return currentUser().userId();
    }

    public static String currentUsername() {
        return currentUser().username();
    }
}
