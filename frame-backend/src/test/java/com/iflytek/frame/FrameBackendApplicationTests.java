package com.iflytek.frame;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test: the Spring context must load. Properties here override application.yml
 * so the test doesn't need a running MariaDB to verify wiring (it still needs the
 * driver on the classpath, which the pom provides at runtime scope — see README if
 * the context fails to load without a DB).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600",
        "frame.cors.allowed-origins=http://localhost:5173"
})
class FrameBackendApplicationTests {

    @Test
    void contextLoads() {
        // If the application context builds, the core wiring (security, mybatis-plus,
        // springdoc, exception handling) is consistent.
    }
}
