package com.iflytek.frame;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.dto.*;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.*;
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
 * End-to-end integration test: the full Redis→Stream→consumer→DB→payment path against
 * REAL Redis + MariaDB (no mocks).
 *
 * <p>Proves the Task 5 machinery works as designed:
 * <ol>
 *   <li>Register a user.</li>
 *   <li>Checkout 1 unit of a flash-sale product → {@code checkoutService.checkout}
 *       Lua-gates stock, XADDs to {@code orders:pending}, sets
 *       {@code order_status:{corrId}="queued"}, returns 202.</li>
 *   <li>The {@code @Scheduled} consumer drains the stream within ~100ms, durably persists
 *       the Order (DB auto-gen PK), decrements DB stock, and sets
 *       {@code order_status:{corrId}="pending"} + {@code order_id_map:{corrId}=<dbPk>}.</li>
 *   <li>Poll until the queued→pending transition lands (this is the proof the consumer ran).</li>
 *   <li>{@code paymentService.pay} flips PENDING→PAID via the atomic conditional update.</li>
 * </ol>
 *
 * <p>Why poll the Redis {@code order_status:{corrId}} key (not just
 * {@code orderService.getById().getStatus()}): the hot path sets {@code order_status="queued"},
 * and {@code getById} returns a <em>synthetic</em> PENDING Order for the queued case so the
 * frontend can poll. That synthetic order's status is non-null immediately — so a
 * {@code .isNotNull()} assertion would pass WITHOUT the consumer ever running. Polling the
 * Redis key for the literal {@code "pending"} value proves the consumer actually committed
 * the durable write.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class CheckoutFlowIntegrationTest {

    @Autowired CheckoutService checkoutService;
    @Autowired OrderService orderService;
    @Autowired ProductMapper productMapper;
    @Autowired PaymentService paymentService;
    @Autowired UserService userService;
    @Autowired StringRedisTemplate redis;

    @Test
    void checkout_endToEnd_queuedToPendingToPaid() {
        // 1. register a user (UC001 SMS-gated: pre-seed the code in Redis so the dev
        //    LoggingVerificationProvider verifyAndConsume succeeds; password must satisfy
        //    the ≥8 / ≥2-category rule; username must be 4-25 alphanumeric — no underscores,
        //    and must not contain a banned word like "test"/"admin"/"root").
        String phone = "139" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "123456";
        redis.opsForValue().set("sms:code:REGISTER:" + phone, code,
                java.time.Duration.ofSeconds(300));
        User user = userService.register(
                new RegisterRequest("buyer" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                        "Pass1234!", phone, code, true));

        // 2. a flash-sale product with stock 1
        Product p = new Product();
        p.setName("ITest Widget");
        p.setPriceInCents(500);
        p.setStock(1);
        productMapper.insert(p);
        // load stock into Redis (open the sale) — the Lua gate reads stock:{pid} from Redis
        redis.opsForValue().set("stock:" + p.getId(), String.valueOf(p.getStock()));

        // 3. checkout 1 unit → hot path: Lua-gate + XADD + order_status=queued + 202
        CheckoutRequest req = new CheckoutRequest();
        OrderItemDTO item = new OrderItemDTO();
        item.setProductId(p.getId());
        item.setQuantity(1);
        req.setItems(List.of(item));
        req.setCustomerName("x");
        req.setCustomerEmail("x@x");
        req.setShippingAddress("y");
        req.setIdempotencyKey(UUID.randomUUID().toString());
        CheckoutResponse resp = checkoutService.checkout(req, user.getId());

        assertThat(resp.getStatus()).isEqualTo("queued");
        assertThat(resp.getOrderId()).isNotNull();
        // hot path sets order_status:{corrId} = "queued" in Redis. The consumer drains
        // every 100ms, so by the time we read, the order may already be "pending" —
        // accept either (both prove the order entered the pipeline).
        assertThat(redis.opsForValue().get("order_status:" + resp.getOrderId()))
                .isIn("queued", "pending");

        // 4. poll until the consumer transitions queued→pending (durable write committed).
        //    The consumer's @Scheduled drain() runs every 100ms.
        Long corrId = resp.getOrderId();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(redis.opsForValue().get("order_status:" + corrId)).isEqualTo("pending"));

        // 5. the persisted Order resolves via order_id_map:{corrId}→DB PK, status PENDING
        Order persisted = orderService.getById(resp.getOrderId(), user.getId());
        assertThat(persisted.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(persisted.getId()).isNotNull();

        // 6. pay → atomic flipStatus(PENDING→PAID) conditional update succeeds
        Order paid = paymentService.pay(resp.getOrderId(), user.getId());
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.PAID);

        // 7. DB stock was decremented by the consumer's DB-backstop (1 unit claimed)
        assertThat(productMapper.selectById(p.getId()).getStock()).isZero();
    }
}
