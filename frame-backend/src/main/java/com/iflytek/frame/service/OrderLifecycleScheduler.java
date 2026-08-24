package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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
 */
@Component
@RequiredArgsConstructor
public class OrderLifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleScheduler.class);
    private final OrderMapper orderMapper;
    private final OrderService orderService;   // for releaseOrderStock on expiry
    private final CoinService coinService;     // UC012 §3: daily expiry sweep

    @Value("${frame.order.expire-minutes:30}")
    private int expireMinutes;

    @Value("${frame.order.complete-after-days:7}")
    private int completeAfterDays;

    /** UC004: cancel PENDING orders older than the expiry window. */
    @Scheduled(fixedDelay = 60_000)
    public void expirePendingOrders() {
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
        }
    }

    /** Auto-complete DELIVERED orders older than completeAfterDays. */
    @Scheduled(cron = "0 7 4 * * *", zone = "Asia/Shanghai")   // daily 4:07am CST, off the :00 herd
    public void autoCompleteOrders() {
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
        }
    }

    /** UC012 §3: daily sweep of coins past their 30-day clock (4:01am CST, off the :00 herd). */
    @Scheduled(cron = "1 1 4 * * *", zone = "Asia/Shanghai")
    public void sweepExpiredCoins() {
        try {
            coinService.sweepExpiredCredits();
        } catch (Exception e) {
            log.error("sweepExpiredCoins failed", e);
        }
    }
}
