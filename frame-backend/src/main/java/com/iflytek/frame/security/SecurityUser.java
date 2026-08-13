package com.iflytek.frame.security;

import io.jsonwebtoken.Claims;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Lightweight authenticated principal populated from JWT claims. Stored as the
 * {@code SecurityContextHolder} authentication principal so controllers/services
 * can read the current user without a DB lookup.
 */
public record SecurityUser(Long userId, String username,
                           Collection<Authority> authorities) {

    @Serial
    private static final long serialVersionUID = 1L;

    public static SecurityUser from(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String username = String.valueOf(claims.get("username"));
        return new SecurityUser(userId, username, List.of(new Authority("ROLE_USER")));
    }

    /** Minimal GrantedAuthority impl — a record can't implement an interface cleanly, so static nested class. */
    public record Authority(String role) implements org.springframework.security.core.GrantedAuthority {
        @Override
        public String getAuthority() {
            return role;
        }
    }
}
