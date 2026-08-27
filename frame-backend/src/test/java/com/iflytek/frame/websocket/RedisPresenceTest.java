package com.iflytek.frame.websocket;

import com.iflytek.frame.common.DistributedLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies admin presence is backed by Redis (not a per-JVM map), so an admin that
 * connects on one instance is visible to {@code nextOnlineAdmin()} on a <em>separate</em>
 * instance sharing the same Redis, AND that a crashed instance's admin is swept from
 * the online SET.
 *
 * <p>Uses {@code @SpringBootTest} against the REAL dev Redis (the repo's established
 * integration-test pattern — see {@code LoadMutexTest}, {@code FlashSaleSchedulerIntegrationTest}).
 * Two {@link AdminPresenceRegistry} instances are constructed over the SAME
 * {@link StringRedisTemplate} (the "shared Redis"): instance A's {@code onAdminConnect}
 * writes to the SET, instance B's {@code nextOnlineAdmin()} reads it — proving real
 * cross-instance shared-state visibility, not just that the right ops were issued.
 *
 * <p>Crash-cleanup test: simulate a crash by SADDing an admin to the SET with NO conn
 * counter (the orphaned state a crashed instance leaves behind — its conn-key TTL later
 * expires, but the SET member lingers). Then invoke {@code sweepStaleAdminPresence()}
 * directly and assert the admin is removed, so {@code nextOnlineAdmin()} no longer
 * returns a dead admin.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class RedisPresenceTest {

    @Autowired StringRedisTemplate redis;
    @Autowired DistributedLock lock;

    // two registries = two JVM instances sharing one real Redis + one lock infra
    private AdminPresenceRegistry instanceA() { return new AdminPresenceRegistry(redis, lock); }
    private AdminPresenceRegistry instanceB() { return new AdminPresenceRegistry(redis, lock); }

    private static final String ONLINE_SET = "chat:online-admins";
    private static final String CONN_PREFIX = "chat:admin-conn:";
    private static final String RR_CURSOR = "chat:admin-rr";
    private static final String SWEEP_LOCK = "shedlock:sweepStaleAdminPresence";

    @AfterEach
    void cleanup() {
        // delete any conn keys left from this run, then the SET, then cursors/locks
        Set<String> members = redis.opsForSet().members(ONLINE_SET);
        if (members != null) {
            members.forEach(id -> redis.delete(CONN_PREFIX + id));
        }
        redis.delete(ONLINE_SET);
        redis.delete(RR_CURSOR);
        redis.delete(SWEEP_LOCK);
    }

    @Test
    void adminConnectOnInstanceA_visibleToNextOnlineAdminOnInstanceB() {
        Long adminId = 7L;

        // --- instance A: admin 7 connects ---
        instanceA().onAdminConnect(adminId);

        // A wrote to the SHARED Redis SET — no manual stubbing of the read; instance B
        // sees it because both talk to the same real Redis.
        // --- instance B (separate JVM, same Redis): asks for an online admin ---
        Long picked = instanceB().nextOnlineAdmin();

        assertThat(picked).as("instance B should see the admin instance A registered, "
                + "because presence is backed by the shared Redis SET, not a per-JVM map")
                .isEqualTo(adminId);
    }

    @Test
    void nextOnlineAdmin_returnsNullWhenNoAdminsOnline() {
        // Redis starts empty for this test (cleanup ran in @AfterEach of any prior test)
        Long picked = instanceB().nextOnlineAdmin();

        assertThat(picked).isNull();
    }

    @Test
    void disconnect_decrementsCounterAndRemovesFromSetAtZero() {
        Long adminId = 9L;
        AdminPresenceRegistry a = instanceA();
        a.onAdminConnect(adminId);
        assertThat(a.onlineAdmins()).contains(adminId);

        a.onAdminDisconnect(adminId);

        assertThat(a.onlineAdmins())
                .as("last disconnect must drop the admin from the online SET")
                .doesNotContain(adminId);
        assertThat(redis.opsForValue().get(CONN_PREFIX + adminId))
                .as("counter key deleted on last disconnect")
                .isNull();
    }

    @Test
    void disconnect_keepsAdminInSetWhenOtherConnectionsRemain() {
        Long adminId = 9L;
        AdminPresenceRegistry a = instanceA();
        a.onAdminConnect(adminId);
        a.onAdminConnect(adminId); // 2 connections

        a.onAdminDisconnect(adminId); // 1 remains

        assertThat(a.onlineAdmins()).contains(adminId);
        assertThat(redis.opsForValue().get(CONN_PREFIX + adminId))
                .as("counter decremented but admin still online (1 connection remains)")
                .isEqualTo("1");
    }

    @Test
    void sweep_removesCrashedAdmin_whoseConnCounterExpired() {
        // Simulate a crash: admin is in the online SET but its conn counter is GONE
        // (TTL expired because the instance crashed before refreshing/decrementing it).
        Long crashedAdmin = 77L;
        redis.opsForSet().add(ONLINE_SET, String.valueOf(crashedAdmin));
        // NOTE: NO conn key created — mirrors a crashed instance whose conn-key TTL expired
        assertThat(instanceB().onlineAdmins()).contains(crashedAdmin); // still listed before sweep

        instanceB().sweepStaleAdminPresence();

        assertThat(instanceB().onlineAdmins())
                .as("sweep must SREM an admin whose conn counter is missing (crash cleanup)")
                .doesNotContain(crashedAdmin);
        assertThat(instanceB().nextOnlineAdmin())
                .as("a swept (dead) admin must no longer be returned by nextOnlineAdmin")
                .isNull();
    }

    @Test
    void sweep_keepsLiveAdmin_whoseConnCounterExists() {
        Long liveAdmin = 88L;
        AdminPresenceRegistry a = instanceA();
        a.onAdminConnect(liveAdmin); // real connect: SET member + conn counter

        instanceB().sweepStaleAdminPresence();

        assertThat(instanceB().onlineAdmins())
                .as("a live admin (conn counter present) must NOT be swept")
                .contains(liveAdmin);
        assertThat(instanceB().nextOnlineAdmin()).isEqualTo(liveAdmin);
    }
}
