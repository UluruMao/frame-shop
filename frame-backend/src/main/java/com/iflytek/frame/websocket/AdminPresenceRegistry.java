package com.iflytek.frame.websocket;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks admins currently connected over WebSocket, for round-robin chat-session
 * assignment. A connect increments a per-admin counter (admins can have multiple
 * connections); a disconnect decrements; removal at 0.
 */
@Component
public class AdminPresenceRegistry {

    private final ConcurrentHashMap<Long, AtomicInteger> presence = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong rrCursor = new java.util.concurrent.atomic.AtomicLong(0);

    public void onAdminConnect(Long adminId) {
        presence.computeIfAbsent(adminId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void onAdminDisconnect(Long adminId) {
        AtomicInteger count = presence.get(adminId);
        if (count != null && count.decrementAndGet() <= 0) {
            presence.remove(adminId);
        }
    }

    public Set<Long> onlineAdmins() {
        return Collections.unmodifiableSet(presence.keySet());
    }

    /** Round-robin pick of an online admin, or null if none online. */
    public Long nextOnlineAdmin() {
        Set<Long> admins = presence.keySet();
        if (admins.isEmpty()) return null;
        Long[] arr = admins.toArray(new Long[0]);
        int idx = (int) (Math.abs(rrCursor.getAndIncrement()) % arr.length);
        return arr[idx];
    }
}
