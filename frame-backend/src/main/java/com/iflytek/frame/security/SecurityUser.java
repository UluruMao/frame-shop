package com.iflytek.frame.security;

import com.iflytek.frame.common.Role;
import io.jsonwebtoken.Claims;

import java.security.Principal;
import java.util.Collection;
import java.util.List;

public record SecurityUser(Long userId, String username, Role role,
                           Collection<Authority> authorities) implements Principal {

    public static SecurityUser from(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String username = String.valueOf(claims.get("username"));
        Role role = Role.valueOf(String.valueOf(claims.get("role")));
        String authorityName = "ROLE_" + role.name();
        return new SecurityUser(userId, username, role,
                List.of(new Authority(authorityName)));
    }

    @Override
    public String getName() {
        return username;
    }

    public record Authority(String role) implements org.springframework.security.core.GrantedAuthority {
        @Override
        public String getAuthority() {
            return role;
        }
    }
}
