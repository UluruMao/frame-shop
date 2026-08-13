package com.iflytek.frame.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the {@code Authorization: Bearer <token>} header, validates the JWT, and
 * populates the {@code SecurityContextHolder} with a {@link SecurityUser} principal.
 * Runs once per request inside the Spring Security filter chain.
 *
 * <p>If the header is missing or the token is invalid, the chain simply proceeds
 * without an authentication — the {@code SecurityConfig} permit-all rules then
 * decide whether the request is allowed (login/register are public, everything else
 * requires authentication and will be rejected with 401).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                   @NonNull HttpServletResponse response,
                                   @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(jwtUtil.getHeader());
        if (!StringUtils.hasText(header) || !header.startsWith(jwtUtil.getTokenPrefix())) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(jwtUtil.getTokenPrefix().length()).trim();
        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            // Invalid/expired token — leave context empty; downstream auth rules reject if needed.
            log.debug("JWT rejected for {} {}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityUser principal = SecurityUser.from(claims);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
