package com.iflytek.frame;

import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.dto.RegisterRequest;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.service.StreamConsumerService;
import com.iflytek.frame.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * UC005 coin branch at checkout — the user opts to spend coins; the hot path validates
 * (flash-sale gating — coins disabled only when the whole cart is flash; cap = min(balance, non-flash subtotal)); the async consumer deducts coins atomically with
 * the order + records {@code paid_in_cents}.
 *
 * <p>Mirrors {@link CheckoutFlowIntegrationTest}'s register/seed/poll approach (SMS code
 * seeded in Redis + {@code userService.register}; Awaitility poll for the consumer's
 * queued→pending transition).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CheckoutWithCoinsIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired CoinService coinService;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;
    @Autowired UserService userService;
    @Autowired StringRedisTemplate redis;
    @Autowired StreamConsumerService streamConsumerService;

    /**
     * Drain any leftover payloads queued by earlier tests OR prior JVM runs. The
     * {@code orders:pending} Redis Stream is shared across the whole suite and never cleaned
     * between tests; a stale payload from a prior JVM run could even share this test's
     * correlation id ({@code CheckoutServiceImpl.ORDER_SEQ} is seeded from
     * {@code System.currentTimeMillis()*1000}, so a fresh JVM can reissue a prior corrId). XREAD
     * (no consumer group) + XDEL each leftover entry until the stream is empty, give the
     * {@code @Scheduled} consumer a beat to finish any in-flight XREADGROUP'd payload, then
     * drain again so THIS test's checkout isn't preceded (or racing) with a stale payload that
     * could map its corrId to an old order.
     */
    @org.junit.jupiter.api.BeforeEach
    void clearPendingQueue() throws InterruptedException {
        drainOrdersPending();
        Thread.sleep(150); // let the @Scheduled consumer finish any in-flight XREADGROUP'd payload
        drainOrdersPending();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drainOrdersPending() {
        // XREAD (no consumer group) to peek leftover entries, then XDEL them so they're never
        // processed (avoids a stale payload's DB write interfering with this test). The consumer
        // group tracks via $ (lastConsumed) so XDEL'd entries simply vanish from the stream.
        try {
            while (true) {
                var recs = redis.opsForStream().read(
                        org.springframework.data.redis.connection.stream.StreamReadOptions.empty().count(100),
                        org.springframework.data.redis.connection.stream.StreamOffset.create(
                                "orders:pending",
                                org.springframework.data.redis.connection.stream.ReadOffset.from("0")));
                if (recs == null || recs.isEmpty()) break;
                java.util.List<org.springframework.data.redis.connection.stream.RecordId> ids =
                        new java.util.ArrayList<>();
                for (var rec : recs) ids.add(rec.getId());
                redis.opsForStream().delete("orders:pending",
                        ids.toArray(new org.springframework.data.redis.connection.stream.RecordId[0]));
            }
        } catch (Exception ignored) {
            // stream absent on a fresh run → nothing to drain
        }
    }

    /**
     * Register a test user via the real UC001 SMS-gated path (code pre-seeded in Redis so
     * the dev LoggingVerificationProvider verifyAndConsume succeeds). The username uses a
     * {@code buyer}+UUID prefix to avoid banned words ("test"/"admin"/"root") and the
     * 4-25 alphanumeric rule. Returns the new user's DB id.
     */
    private Long registerTestUser() {
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "123456";
        redis.opsForValue().set("sms:code:REGISTER:" + phone, code, java.time.Duration.ofSeconds(300));
        User user = userService.register(new RegisterRequest(
                "buyer" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "Pass1234!", phone, code, true));
        return user.getId();
    }

    private Long seedNonFlashProduct(int priceCents, int stock) {
        Product p = new Product();
        p.setName("CoinTest-" + System.nanoTime());
        p.setPriceInCents(priceCents);
        p.setStock(stock);
        p.setCategory("test");
        p.setFlashSale(false);
        productMapper.insert(p);
        // load stock into Redis (open the sale) — the Lua gate reads stock:{pid} from Redis
        redis.opsForValue().set("stock:" + p.getId(), String.valueOf(p.getStock()));
        return p.getId();
    }

    private Long seedFlashProduct(int priceCents, int stock) {
        Product p = new Product();
        p.setName("CoinTestFlash-" + System.nanoTime());
        p.setPriceInCents(priceCents);
        p.setStock(stock);
        p.setCategory("test");
        p.setFlashSale(true);
        productMapper.insert(p);
        redis.opsForValue().set("stock:" + p.getId(), String.valueOf(p.getStock()));
        return p.getId();
    }

    private OrderItemDTO item(Long pid, int qty) {
        OrderItemDTO i = new OrderItemDTO();
        i.setProductId(pid);
        i.setQuantity(qty);
        return i;
    }

    @Test
    void checkout_withCoins_deductsAndSetsPaidInCents() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100 coins (100 coins = ¥1 discount)
        Long pid = seedNonFlashProduct(20000, 5); // ¥200; cap = min(balance 100, non-flash subtotal 20000c) = 100
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(item(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000001");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setUseCoins(true); req.setCoinAmount(100);

        CheckoutResponse res = checkoutService.checkout(req, uid);
        Long corrId = res.getOrderId();
        // Wipe any stale Redis state for THIS corrId left by a prior JVM run (ORDER_SEQ is
        // seeded from millis, so a fresh JVM can reissue a prior corrId — a stale
        // order_id_map:{corrId} would resolve our checkout to an OLD order with paid=0).
        // The hot path just set order_status:{corrId}="queued"; deleting it is fine — the
        // consumer re-sets it from the payload.
        redis.delete("order_status:" + corrId);
        redis.delete("order_id_map:" + corrId);
        // Force-drain the orders:pending List (don't rely solely on the @Scheduled drain(),
        // which shares a 4-thread ThreadPoolTaskScheduler and can be starved under full-suite
        // load). drain() RPOPs + submits each payload to the consumer pool, which calls
        // self.processPayload through the Spring proxy (activating @Transactional). Then poll
        // for the committed row. getById retries until the consumer tx commits (order_id_map
        // is set inside the tx, before commit).
        streamConsumerService.drain();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(orderService.getById(corrId, uid).getPaidInCents()).isEqualTo(20000 - 100));
        Order o = orderService.getById(corrId, uid);
        assertThat(o.getPaidInCents()).isEqualTo(20000 - 100); // ¥200 - ¥1 = 19900
        assertThat(o.getTotalInCents()).isEqualTo(20000);
        assertThat(o.getIdempotencyKey()).isEqualTo(req.getIdempotencyKey()); // resolved to OUR order
        assertThat(coinService.getBalance(uid).getBalance()).isLessThanOrEqualTo(0);
    }

    @Test
    void checkout_flashSaleItem_withCoins_rejected() {
        Long uid = registerTestUser();
        Long pid = seedFlashProduct(20000, 5);
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(item(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000002");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setUseCoins(true); req.setCoinAmount(100);

        assertThatThrownByIsBusinessException(() -> checkoutService.checkout(req, uid));
    }

    @Test
    void checkout_coinAmountOverCap_rejected() {
        Long uid = registerTestUser();
        coinService.awardDailyLogin(uid); // +100 (balance = cap)
        Long pid = seedNonFlashProduct(20000, 5); // non-flash subtotal 20000c; cap = min(100, 20000) = 100
        CheckoutRequest req = new CheckoutRequest();
        req.setItems(List.of(item(pid, 1)));
        req.setCustomerName("t"); req.setCustomerEmail("t@t.com");
        req.setShippingAddress("addr"); req.setCustomerPhone("13900000003");
        req.setPaymentMethod("WECHAT"); req.setIdempotencyKey(UUID.randomUUID().toString());
        req.setUseCoins(true); req.setCoinAmount(300); // > 100 cap (balance-limited)

        assertThatThrownByIsBusinessException(() -> checkoutService.checkout(req, uid));
    }

    /** Assert the callable throws a BusinessException (flash-sale / over-cap are both PARAM_INVALID). */
    private static void assertThatThrownByIsBusinessException(Runnable callable) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable::run)
                .isInstanceOf(BusinessException.class);
    }
}
