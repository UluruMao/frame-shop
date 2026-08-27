package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that PROVES {@link UserServiceImpl#deactivateAccount(Long)} actually
 * anonymizes PII against the real DB (MariaDB) + real MyBatis-Plus.
 *
 * <p>This test exists because the plain-Mockito {@code AccountServiceImplTest} mocks
 * {@code UserService.deactivateAccount} (asserts it is *called*, never what it *does*) —
 * so a silent regression where the UPDATE never persisted the null PII fields went
 * undetected. This test exercises the real {@code UserServiceImpl} (auto-wired, full
 * Spring context) against the real schema and asserts the resulting row state directly.
 *
 * <p>Specifically guards against two regressions:
 * <ol>
 *   <li>MyBatis-Plus {@code updateById} with default {@code FieldStrategy.NOT_NULL}
 *       silently drops null fields — phone/nickname/avatar_url/signature would stay
 *       populated. The impl uses an explicit {@code UpdateWrapper.set("col", null)}
 *       which emits literal {@code SET col=NULL}.</li>
 *   <li>{@code password_hash} is {@code NOT NULL} in the schema (schema.sql:20), so
 *       it cannot be nulled. The impl sets it to the sentinel {@code "!DELETED!"}
 *       (not a valid BCrypt hash → un-loginable) instead.</li>
 * </ol>
 *
 * <p>{@code @Transactional} rolls back after each test so the shared integration DB
 * is not polluted by the seeded user row.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
@Transactional
class UserServiceImplDeactivateAccountIntegrationTest {

    @Autowired UserService userService;
    @Autowired UserMapper userMapper;

    @Test
    void deactivateAccount_anonymizesPii_andSetsSentinelPasswordAndDeletedStatus() {
        // 1. Seed a user WITH PII populated (the state that must be cleared).
        User seed = new User();
        seed.setUsername("toDelete" + System.nanoTime());
        seed.setPasswordHash("$2a$10$originalValidBcryptHashPlaceholder123456789012");
        seed.setRole(Role.USER);
        seed.setPhone("13900077777");
        seed.setNickname("Real Name");
        seed.setAvatarUrl("https://cdn.example.com/avatar/777.png");
        seed.setSignature("my personal signature");
        seed.setStatus("ACTIVE");
        seed.setFailedAttempts(0);
        userMapper.insert(seed);

        // 2. Soft-delete via the service under test.
        userService.deactivateAccount(seed.getId());

        // 3. Re-read straight from the mapper (bypass any cache) and assert the anonymized state.
        User reloaded = userMapper.selectById(seed.getId());
        assertThat(reloaded).as("user row must still exist (soft-delete retains the row)").isNotNull();

        // PII must be NULLed — these assertions fail under the buggy updateById (NOT_NULL
        // strategy omits nulls, so the columns keep their seeded values).
        assertThat(reloaded.getPhone()).isNull();
        assertThat(reloaded.getNickname()).isNull();
        assertThat(reloaded.getAvatarUrl()).isNull();
        assertThat(reloaded.getSignature()).isNull();

        // password_hash is NOT NULL in the schema, so it is set to the "!DELETED!" sentinel
        // (not a valid BCrypt hash → passwordEncoder.matches always returns false).
        assertThat(reloaded.getPasswordHash()).isEqualTo("!DELETED!");

        // Username is suffixed with the id so it stays unique (never collides with a future
        // registration of the same name).
        assertThat(reloaded.getUsername()).isEqualTo("[已注销]" + seed.getId());

        // Status marks the account as soft-deleted (the JWT filter revokes these tokens).
        assertThat(reloaded.getStatus()).isEqualTo("DELETED");
    }
}
