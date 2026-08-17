package com.iflytek.frame.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.Result;
import com.iflytek.frame.common.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-user token-bucket rate limiter on {@code POST /checkout}.
 *
 * <p>Runs <em>after</em> {@link JwtAuthenticationFilter} so the
 * {@code SecurityContextHolder} already holds the authenticated {@link SecurityUser}.
 * For every checkout attempt we atomically increment a Redis counter keyed by
 * {@code rate_limit:{userId}:{minuteBucket}} (minuteBucket = epoch-seconds / 60,
 * so the bucket flips exactly on the wall-clock minute boundary). The first
 * increment in a window also sets a 60s TTL so stale buckets self-expire. If the
 * count exceeds {@code frame.rate-limit.checkout-per-minute}, the request is
 * rejected with HTTP 429 + a {@code Result.fail(TOO_MANY_REQUESTS, ...)} body —
 * the seckill hot path is never reached.
 *
 * <p>If there is no authenticated user (anon request), the filter is a no-op:
 * the downstream Spring Security authorization rules will 401 the request. If
 * Redis is down, we fail <em>open</em> (allow the request through) so a Redis
 * outage doesn't hard-stop all checkouts — the Lua stock gate still provides
 * the correctness guarantee, and fail-open on a pre-auth limiter is safer than
 * failing closed and locking out every buyer.
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHECKOUT_PATH = "/checkout";
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    private static final Duration KEY_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final RateLimitProperties rateLimitProperties;
    private final ApplicationContext applicationContext;

    /** Resolved lazily so the filter doesn't hard-require an ObjectMapper bean at startup. */
    private ObjectMapper objectMapper() {
        try {
            return applicationContext.getBean(ObjectMapper.class);
        } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException e) {
            return new ObjectMapper();
        }
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                   @NonNull HttpServletResponse response,
                                   @NonNull FilterChain chain) throws ServletException, IOException {

        // Only throttle the checkout hot path.
        if (!isCheckoutPost(request)) {
            chain.doFilter(request, response);
            return;
        }

        Long userId = currentUserId();
        if (userId == null) {
            // No authenticated principal — let the security chain 401 it.
            chain.doFilter(request, response);
            return;
        }

        int limit = rateLimitProperties.getCheckoutPerMinute();
        // Bucket = epoch-seconds floored to the minute. Flips exactly on each wall-clock minute.
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String key = RATE_LIMIT_KEY_PREFIX + userId + ":" + minuteBucket;

        Long count;
        try {
            // INCR is atomic in Redis: even under 50-thread concurrency each buyer
            // gets a distinct count. The first INCR creates the key; we then set TTL.
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, KEY_TTL);
            }
        } catch (Exception e) {
            // Redis down — fail open (see class javadoc). Log + proceed; the Lua gate
            // downstream is still the source of truth for stock correctness.
            log.warn("Rate-limit Redis check failed for userId={} on /checkout — failing open: {}",
                    userId, e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (count != null && count > limit) {
            log.debug("Rate limit exceeded for userId={} on /checkout (count={}, limit={})",
                    userId, count, limit);
            writeTooManyRequests(response);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isCheckoutPost(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        // Context-path is "/" so the URI is "/checkout" (+ possible trailing slash/query).
        // Match by prefix on the path segment to be tolerant of trailing slashes.
        return CHECKOUT_PATH.equalsIgnoreCase(uri)
                || uri != null && uri.equalsIgnoreCase(CHECKOUT_PATH + "/");
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SecurityUser user)) {
            return null;
        }
        return user.userId();
    }

    private void writeTooManyRequests(HttpServletResponse response) throws IOException {
        // 429 — SC_TOO_MANY_REQUESTS isn't in the Servlet 6.0 API, so use the literal.
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                objectMapper().writeValueAsString(
                        Result.fail(ResultCode.TOO_MANY_REQUESTS, "rate limit exceeded")));
    }
}
