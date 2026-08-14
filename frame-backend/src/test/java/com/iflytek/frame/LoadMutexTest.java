package com.iflytek.frame;

import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The mentor's headline proof-of-correctness artifact.
 *
 * <p>Spawns M=50 concurrent threads — each a distinct registered user — each POSTing a
 * checkout for 1 unit of a product with stock K=5. The Redis Lua gate
 * ({@code stock_gate.lua}) is the atomic all-or-nothing mutex: exactly K buyers claim a
 * unit, the remaining (M−K) get 409 "sold out". The consumer then drains the stream and
 * DB-backstop-decrements stock to 0.
 *
 * <p>This is the no-oversell + no-double-order proof under real concurrency against real
 * Redis + MariaDB. If the Lua gate, consumer-group, idempotency, or XACK-after-commit path
 * has a hidden bug, it surfaces here:
 * <ul>
 *   <li>Oversell → {@code ok202 > K} (the Lua gate failed to be atomic).</li>
 *   <li>Undersell → {@code ok202 < K} (legit buyers blocked — e.g. the one-per-user key
 *       interfering, or a Lua-script bug decrementing too much).</li>
 *   <li>Stock drift → final DB or Redis stock ≠ 0 (consumer double-decremented, or
 *       re-credited a claimed unit, or orders lost in the consumer).</li>
 * </ul>
 *
 * <p><b>KNOWN FAILURE (production-code bug revealed by this test):</b> The checkout hot path
 * ({@code CheckoutServiceImpl}) generates the order correlation id as
 * {@code System.currentTimeMillis()}. Under 50-thread concurrency, multiple buyers collide
 * on the same millis orderId. This triggers a Spring Data Redis {@code MapRecord}
 * deserialization issue where the consumer reads the WRONG payload for a given stream
 * record — 5 distinct buyers' messages collapse into ~2 distinct persisted orders, and
 * DB-backstop stock decrements fall short (stock ends at 2-3, not 0). The Lua gate itself
 * is correct (ok202=5, conflict409=45, Redis stock=0), but the consumer loses orders. This
 * is a real production-code defect in the Task 5 orderId strategy, not a test bug.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class LoadMutexTest {

    @Autowired CheckoutService checkoutService;
    @Autowired ProductMapper productMapper;
    @Autowired UserService userService;
    @Autowired OrderService orderService;
    @Autowired StockGateService stockGateService;
    @Autowired StringRedisTemplate redis;

    @Test
    void concurrentBuyers_exactlyKsucceed_restSoldOut_finalStockZero() throws Exception {
        int K = 5;          // stock
        int M = 50;         // distinct buyers
        Product p = new Product();
        p.setName("Mutex Widget");
        p.setPriceInCents(100);
        p.setStock(K);
        productMapper.insert(p);
        // load stock into Redis (open the sale) — required before checkout can claim.
        // The Lua gate reads stock:{pid} from Redis; without this ALL checkouts fail sold-out.
        stockGateService.loadStock(p.getId());

        ExecutorService pool = Executors.newFixedThreadPool(M);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok202 = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();

        List<Runnable> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < M; i++) {
            final int idx = i;
            tasks.add(() -> {
                try {
                    start.await();
                    User u = userService.register(
                            new RegisterRequest("lb_" + idx + "_" + UUID.randomUUID(), "pass1234"));
                    CheckoutRequest req = new CheckoutRequest();
                    OrderItemDTO item = new OrderItemDTO();
                    item.setProductId(p.getId());
                    item.setQuantity(1);
                    req.setItems(List.of(item));
                    req.setCustomerName("n");
                    req.setCustomerEmail("e");
                    req.setShippingAddress("a");
                    req.setIdempotencyKey(UUID.randomUUID().toString());
                    try {
                        checkoutService.checkout(req, u.getId());
                        ok202.incrementAndGet();
                    } catch (BusinessException be) {
                        if (be.getCode() == 409) conflict409.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // unexpected thread-level failure — the assertions below will catch a shortfall
                }
            });
        }
        tasks.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        // wait for the consumer to drain the K claimed orders (queued→pending).
        // The consumer's @Scheduled drain() runs every 500ms; K orders × 1 unit each.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Product current = productMapper.selectById(p.getId());
            assertThat(current.getStock()).isLessThanOrEqualTo(0);
        });

        // ---- the headline assertions: no oversell, no undersell, no stock drift ----
        assertThat(ok202.get()).isEqualTo(K);          // exactly K claimed stock (no oversell/undersell)
        assertThat(conflict409.get()).isEqualTo(M - K); // rest got sold-out 409
        assertThat(productMapper.selectById(p.getId()).getStock()).isZero();  // DB stock = 0
        // Redis stock = 0 too (the Lua gate decremented K units atomically; no drift)
        assertThat(redis.opsForValue().get("stock:" + p.getId())).isEqualTo("0");
    }
}
