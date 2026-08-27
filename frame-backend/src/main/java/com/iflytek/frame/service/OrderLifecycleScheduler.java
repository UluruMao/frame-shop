package com.iflytek.frame.service;

import com.iflytek.frame.common.DistributedLock;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled maintenance for the order lifecycle.
 *
 * <ul>
 *   <li>UC004: expire PENDING orders older than the payment window — the buyer never paid,
 *       so the stock must be released back for the next buyer. The CAS PENDING→CANCELLED
 *       guards against a late payment racing the expiry; {@code expireOrder} recredits stock.</li>
 *   <li>Auto-complete DELIVERED orders after a confirmation window — assumes the buyer
 *       implicitly accepted receipt. The CAS DELIVERED→COMPLETED is no-op if state moved.</li>
 * </ul>
 *
 * <p>Both ticks swallow per-order exceptions so one bad row doesn't kill the whole sweep;
 * the outer try/catch guards against a query-level failure (e.g. transient DB blip).
 *
 * <p>Leader election: each tick first acquires a {@code shedlock:{taskName}} Redis lock
 * (SET NX EX, ttl slightly above the cadence) so only one of N instances runs it per tick;
 * the lock is released in {@code finally} via Lua compare-and-delete. TTLs: 90s for the
 * 60s {@code expirePendingOrders} cadence; 5min for the two daily cron jobs.
 */
@Component
@RequiredArgsConstructor
public class OrderLifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleScheduler.class);

    /**
     * Per-instance stable id used as the distributed-lock owner. Read once at class-load from
     * the {@code HOSTNAME} env var (set by the container runtime); falls back to a UUID that is
     * stable for the JVM's lifetime. Same approach on
     * {@link com.iflytek.frame.service.impl.StockGateServiceImpl}.
     */
    private static final String INSTANCE_ID =
            System.getenv().getOrDefault("HOSTNAME", "instance-" + UUID.randomUUID());

    private final OrderMapper orderMapper;
    private final OrderService orderService;   // for releaseOrderStock on expiry
    private final CoinService coinService;     // UC012 §3: daily expiry sweep
    private final DistributedLock lock;

    @Value("${frame.order.expire-minutes:30}")
    private int expireMinutes;

    @Value("${frame.order.complete-after-days:7}")
    private int completeAfterDays;

    /** UC004: cancel PENDING orders older than the expiry window. */
    @Scheduled(fixedDelay = 60_000)
    public void expirePendingOrders() {
        if (!lock.tryLock("shedlock:expirePendingOrders", INSTANCE_ID, Duration.ofSeconds(90))) {
            return; // another instance is the leader for this tick
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(expireMinutes);
            List<Order> stale = orderMapper.selectPendingBefore(cutoff);
            for (Order o : stale) {
                try {
                    orderService.expireOrder(o.getId());   // CAS PENDING→CANCELLED + releaseOrderStock
                } catch (Exception e) {
                    log.warn("expire order {} failed: {}", o.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("expirePendingOrders tick failed: {}", e.getMessage());
        } finally {
            lock.unlock("shedlock:expirePendingOrders", INSTANCE_ID);
        }
    }

    /** Auto-complete DELIVERED orders older than completeAfterDays. */
    @Scheduled(cron = "0 7 4 * * *", zone = "Asia/Shanghai")   // daily 4:07am CST, off the :00 herd
    public void autoCompleteOrders() {
        if (!lock.tryLock("shedlock:autoCompleteOrders", INSTANCE_ID, Duration.ofMinutes(5))) {
            return; // another instance is the leader for this tick
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(completeAfterDays);
            List<Order> stale = orderMapper.selectDeliveredBefore(cutoff);
            for (Order o : stale) {
                try {
                    orderService.completeOrder(o.getId());  // CAS DELIVERED→COMPLETED + completed_at
                } catch (Exception e) {
                    log.warn("complete order {} failed: {}", o.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("autoCompleteOrders tick failed: {}", e.getMessage());
        } finally {
            lock.unlock("shedlock:autoCompleteOrders", INSTANCE_ID);
        }
    }

    /** UC012 §3: daily sweep of coins past their 30-day clock (4:01am CST, off the :00 herd). */
    @Scheduled(cron = "1 1 4 * * *", zone = "Asia/Shanghai")
    public void sweepExpiredCoins() {
        if (!lock.tryLock("shedlock:sweepExpiredCoins", INSTANCE_ID, Duration.ofMinutes(5))) {
            return; // another instance is the leader for this tick
        }
        try {
            coinService.sweepExpiredCredits();
        } catch (Exception e) {
            log.error("sweepExpiredCoins failed", e);
        } finally {
            lock.unlock("shedlock:sweepExpiredCoins", INSTANCE_ID);
        }
    }
}
