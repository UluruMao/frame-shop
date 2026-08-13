package com.iflytek.frame.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates and validates HS256 JWTs. Claims used:
 * <ul>
 *   <li>{@code sub} — user id (as string)</li>
 *   <li>{@code username} — login name</li>
 *   <li>{@code iat}/{@code exp} — issued-at / expiry</li>
 * </ul>
 *
 * <p>Stateless: no DB lookup is needed to validate a token, which is what lets
 * multiple backend instances serve requests independently.
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final JwtProperties props;
    private SecretKey key;

    @PostConstruct
    void init() {
        byte[] bytes = props.getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            log.warn("frame.jwt.secret is shorter than 256 bits — generate a strong secret before production use.");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** Build a signed JWT for the given user. */
    public String generateToken(Long userId, String username, com.iflytek.frame.common.Role role) {
        java.util.Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("username", username);
        claims.put("role", role.name());
        java.util.Date now = new java.util.Date();
        java.util.Date expiry = new java.util.Date(now.getTime() + props.getExpire() * 1000L);
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** Parse + verify a token. Returns null (and logs) on any failure. */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return null;
        }
    }

    public boolean isValid(String token) {
        return parse(token) != null;
    }

    public long getExpireSeconds() {
        return props.getExpire();
    }

    public String getHeader() {
        return props.getHeader();
    }

    public String getTokenPrefix() {
        return props.getTokenPrefix();
    }
}
