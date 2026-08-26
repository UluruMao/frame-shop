package com.iflytek.frame.security;

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
 * Security posture proof for Task 21: admin endpoints are enforced at the
 * <em>method</em> level via {@code @PreAuthorize("hasRole('ADMIN')")}, in
 * addition to the URL-level {@code hasRole("ADMIN")} matchers in
 * {@code SecurityConfig} (defense-in-depth — both layers retained).
 *
 * <p>This is the test that would go RED and silently pass a security gap if
 * {@code @EnableMethodSecurity} were missing: without it, {@code @PreAuthorize}
 * is a no-op, and a USER-role token would sail through to the controller method
 * (the URL matcher also rejects it, but the point of this task is that the
 * <em>method</em> annotation must be the active enforcement layer).
 *
 * <p>Style mirrors the existing {@code @SpringBootTest(RANDOM_PORT)} integration
 * tests (e.g. {@code ChatIntegrationTest}) — the backend has no MockMvc slice
 * test infrastructure, so a full-context test against real Redis + MariaDB is
 * the lightest approach that compiles and runs here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class AdminEndpointSecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    private final RestClient rest = RestClient.create();

    /**
     * A USER-role JWT (authenticated, but non-admin) calling an admin endpoint
     * must be rejected with 403 Forbidden. This is the core guarantee of the
     * {@code @PreAuthorize("hasRole('ADMIN')")} migration: enforcement lives at
     * the method, not only the URL matcher.
     */
    @Test
    void nonAdminJwt_callingAdminEndpoint_returns403() {
        String userToken = jwtUtil.generateToken(99001L, "plainuser", Role.USER);

        HttpClientErrorException ex = callAdminOrders(userToken);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * An unauthenticated call (no JWT at all) to an admin endpoint must be
     * rejected with 401 Unauthorized — the URL-level matcher rejects it before
     * the method is ever reached. This documents the defense-in-depth first
     * layer (which is intentionally retained).
     */
    @Test
    void noAuth_callingAdminEndpoint_returns401() {
        HttpClientErrorException ex = callAdminOrders(null);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** GET /admin/orders with the given Bearer token (or none). Captures the
     *  HttpClientErrorException thrown by the security layer's 401/403. */
    private HttpClientErrorException callAdminOrders(String token) {
        try {
            rest.get()
                    .uri("http://localhost:" + port + "/admin/orders")
                    .headers(h -> { if (token != null) h.setBearerAuth(token); })
                    .retrieve()
                    .toBodilessEntity();
            throw new AssertionError("expected admin endpoint to be rejected, but it returned 2xx");
        } catch (HttpClientErrorException e) {
            return e;
        }
    }
}
