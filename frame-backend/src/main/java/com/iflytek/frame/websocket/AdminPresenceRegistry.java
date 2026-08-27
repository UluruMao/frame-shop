package com.iflytek.frame.websocket;

import com.iflytek.frame.common.DistributedLock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tracks admins currently connected over WebSocket, for round-robin chat-session
 * assignment. Backed by Redis so an admin connected to ANY instance is visible to
 * {@code nextOnlineAdmin()} on every instance (multi-instance round-robin).
 *
 * <p>Backing layout (all under the {@code chat:} namespace):
 * <ul>
 *   <li>{@code chat:online-admins} — a Redis SET whose members are the adminIds that
 *       currently have at least one WS connection across the cluster.</li>
 *   <li>{@code chat:admin-conn:{adminId}} — a per-admin connection counter. INCR on
 *       connect, DECR on disconnect; when DECR reaches 0 the admin is SREM-oved from
 *       the online set. A short TTL (120s) is refreshed on each connect so that if an
 *       instance crashes without firing the DISCONNECT path, the counter expires and
 *       the leader-guarded sweep ({@link #sweepStaleAdminPresence()}) detects the
 *       missing/expired counter and SREMs the admin from the SET.</li>
 *   <li>{@code chat:admin-rr} — a cluster-wide round-robin cursor, INCR'd on each
 *       {@code nextOnlineAdmin()} pick and taken mod the online count.</li>
 * </ul>
 *
 * <h3>Staleness / crash cleanup</h3>
 * The conn-key TTL alone is NOT sufficient: it expires the <em>counter</em> but the
 * read path ({@link #onlineAdmins()}/{@link #nextOnlineAdmin()}) consults the SET,
 * which has no per-member TTL. So a crashed instance's admin would linger in the SET
 * indefinitely (no DISCONNECT ever fires to SREM it). The
 * {@link #sweepStaleAdminPresence()} scheduled method closes this gap: every 30s the
 * leader re-reads each SET member's conn counter and SREMs any whose counter is
 * missing/expired (a crashed instance's conn key expires after 120s → the sweep SREMs
 * the admin from the SET). This is the actual crash-cleanup mechanism; the TTL just
 * bounds how stale a counter can get before the sweep catches it.
 *
 * <p>Interface unchanged from the in-JVM version — {@code AuthChannelInterceptor} and
 * {@code ChatServiceImpl} are unaware that presence moved to Redis.
 */
@Component
@RequiredArgsConstructor
public class AdminPresenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdminPresenceRegistry.class);

    private static final String ONLINE_SET = "chat:online-admins";
    private static final String CONN_PREFIX = "chat:admin-conn:";
    private static final String RR_CURSOR = "chat:admin-rr";
    private static final String SWEEP_LOCK = "shedlock:sweepStaleAdminPresence";
    /** Refreshed on every connect so a crashed instance's admin expires. Long enough
     *  to survive a brief Redis blip; short enough that a dead admin is swept within
     *  ~TTL + one sweep tick (120s + 30s). */
    private static final Duration CONN_TTL = Duration.ofSeconds(120);

    /** Per-instance stable id used as the distributed-lock owner (same approach as
     *  {@code StockGateServiceImpl.INSTANCE_ID}). */
    private static final String INSTANCE_ID =
            System.getenv().getOrDefault("HOSTNAME", "instance-" + UUID.randomUUID());

    private final StringRedisTemplate redis;
    private final DistributedLock lock;

    public void onAdminConnect(Long adminId) {
        String id = String.valueOf(adminId);
        String connKey = CONN_PREFIX + id;
        // order matters: register presence first so nextOnlineAdmin never observes a
        // counter of 0 for an admin mid-connect; refresh the TTL as a crash safety net
        // (the sweep relies on this TTL expiring to detect a crashed instance's admin).
        redis.opsForSet().add(ONLINE_SET, id);
        Long count = redis.opsForValue().increment(connKey);
        if (count != null && count > 0) {
            redis.expire(connKey, CONN_TTL);
        }
    }

    public void onAdminDisconnect(Long adminId) {
        String id = String.valueOf(adminId);
        String connKey = CONN_PREFIX + id;
        Long count = redis.opsForValue().decrement(connKey);
        if (count == null || count <= 0) {
            // last connection (or counter already gone) → drop from the online set;
            // also clear the counter key so it doesn't linger at a negative value.
            redis.opsForSet().remove(ONLINE_SET, id);
            redis.delete(connKey);
        }
    }

    public Set<Long> onlineAdmins() {
        Set<String> members = redis.opsForSet().members(ONLINE_SET);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        return members.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Round-robin pick of an online admin across all instances, or null if none online.
     * Reads the shared SET and advances the shared cursor so the pick rotates cluster-wide.
     */
    public Long nextOnlineAdmin() {
        Set<String> members = redis.opsForSet().members(ONLINE_SET);
        if (members == null || members.isEmpty()) {
            return null;
        }
        // deterministic-ish ordering before indexing: a Set has no order, so sort to
        // keep round-robin stable across calls (otherwise the cursor is meaningless).
        // (Redis SET iteration order is unspecified, same as the prior ConcurrentHashMap
        // keySet; sorting only tightens determinism and doesn't change correctness.)
        List<String> sorted = new ArrayList<>(members);
        Collections.sort(sorted);
        Long cursor = redis.opsForValue().increment(RR_CURSOR);
        if (cursor == null) {
            cursor = 0L;
        }
        int idx = (int) (Math.floorMod(cursor, sorted.size()));
        return Long.valueOf(sorted.get(idx));
    }

    /**
     * Leader-guarded staleness sweep: re-reads the conn counter for each SET member and
     * SREMs admins whose counter is missing (TTL-expired from a crashed instance) or 0.
     * This is the actual crash-cleanup mechanism — the conn-key TTL bounds staleness,
     * but only this sweep removes the dead admin from the SET that the read path consults.
     *
     * <p>Leader election: only the instance winning {@code SWEEP_LOCK} runs; others skip.
     * Same {@code shedlock:*} + {@code DistributedLock} pattern as
     * {@code StockGateServiceImpl.openDueFlashSales}.
     */
    @Scheduled(fixedRate = 30_000)
    public void sweepStaleAdminPresence() {
        if (!lock.tryLock(SWEEP_LOCK, INSTANCE_ID, Duration.ofSeconds(40))) {
            return; // another instance is the leader for this sweep tick
        }
        try {
            Set<String> members = redis.opsForSet().members(ONLINE_SET);
            if (members == null || members.isEmpty()) {
                return;
            }
            for (String adminId : members) {
                String connKey = CONN_PREFIX + adminId;
                String count = redis.opsForValue().get(connKey);
                // counter missing (TTL expired — crashed instance) or never set; also
                // treat a "0"/negative residue as stale (defensive: should not happen
                // because disconnect removes at 0, but guards against a race where a
                // DECR-to-0 and a concurrent connect interleave).
                if (count == null || count.equals("0") || count.startsWith("-")) {
                    redis.opsForSet().remove(ONLINE_SET, adminId);
                    log.debug("Swept stale admin {} from online set (conn counter={})",
                            adminId, count);
                }
            }
        } finally {
            lock.unlock(SWEEP_LOCK, INSTANCE_ID);
        }
    }
}
