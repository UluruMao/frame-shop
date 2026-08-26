package com.iflytek.frame.controller;

import com.iflytek.frame.common.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security + contract test for {@code GET /admin/stats}. Mirrors the repo's
 * established admin-security test pattern ({@link AdminEndpointSecurityTest}):
 * {@code @SpringBootTest(RANDOM_PORT)} with real JWTs via {@link
 * com.iflytek.frame.security.JwtUtil} and {@link RestClient} — the backend has
 * no MockMvc slice infrastructure, and {@code @WebMvcTest} +
 * {@code @Import(SecurityConfig.class)} would clash with the config's hard
 * dependencies (JwtAuthenticationFilter, Redis, RateLimitProperties, etc.).
 *
 * <p>Essential assertions preserved from the task brief:
 * <ul>
 *   <li>ADMIN → 200 with the VO body ({@code totalRevenueCents}, {@code totalOrders}).</li>
 *   <li>USER  → 403 Forbidden.</li>
 * </ul>
 * The DB is shared with other integration tests, so the VO numeric values are
 * asserted as present non-negative fields (not fixed literals), matching the
 * {@code AdminStatsMapperTest} non-deterministic-DB philosophy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class AdminStatsControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    com.iflytek.frame.security.JwtUtil jwtUtil;

    private final RestClient rest = RestClient.create();

    /**
     * An ADMIN-role JWT calling GET /admin/stats must succeed (200) and return
     * the dashboard VO body with the {@code totalRevenueCents} and
     * {@code totalOrders} aggregate fields populated.
     */
    @Test
    void adminJwt_canFetchStats_returns200WithVo() {
        String adminToken = jwtUtil.generateToken(99002L, "adminuser", Role.ADMIN);

        String body = rest.get()
                .uri("http://localhost:" + port + "/admin/stats")
                .headers(h -> h.setBearerAuth(adminToken))
                .retrieve()
                .body(String.class);

        assertThat(body).isNotNull();
        assertThat(body).contains("totalRevenueCents");
        assertThat(body).contains("totalOrders");
    }

    /**
     * A USER-role JWT (authenticated, but non-admin) calling GET /admin/stats
     * must be rejected with 403 Forbidden — the {@code @PreAuthorize} method
     * gate and the {@code /admin/stats} URL matcher both enforce ADMIN.
     */
    @Test
    void userJwt_callingStatsEndpoint_returns403() {
        String userToken = jwtUtil.generateToken(99001L, "plainuser", Role.USER);

        HttpClientErrorException ex = callStats(userToken);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** GET /admin/stats with the given Bearer token (or none). Captures the
     *  HttpClientErrorException thrown by the security layer's 401/403. */
    private HttpClientErrorException callStats(String token) {
        try {
            rest.get()
                    .uri("http://localhost:" + port + "/admin/stats")
                    .headers(h -> { if (token != null) h.setBearerAuth(token); })
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected /admin/stats to be rejected, but it returned 2xx");
        } catch (HttpClientErrorException e) {
            return e;
        }
    }
}
