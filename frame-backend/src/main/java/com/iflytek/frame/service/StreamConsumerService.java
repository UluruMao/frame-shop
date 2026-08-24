package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PaymentMethod;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Consumer-group consumer for the {@code orders:pending} stream — the durable-write half
 * of the Taobao seckill model.
 *
 * <p>The hot path ({@link com.iflytek.frame.service.impl.CheckoutServiceImpl#checkout})
 * Lua-gates stock and XADDs an order message; this consumer drains the stream via
 * {@code XREADGROUP}, durably persists the order (idempotency-guarded insert), runs the
 * DB-backstop stock decrement, inserts line items, and XACKs <strong>only after the
 * transaction commits</strong>.
 *
 * <h3>Order ID strategy</h3>
 * The payload carries a {@code orderId} (millis-based correlation id) used for two Redis
 * keys: {@code order_status:{orderId}} (visible status) and {@code order_id_map:{orderId}}
 * (correlation-id→DB-PK map so the client can resolve its 202 correlation id to the real
 * Order row). The Order row's PK is DB AUTO_INCREMENT (NOT set from the payload) — this
 * avoids same-millisecond PK collisions. OrderItem FK uses {@code order.getId()} (the
 * DB-generated PK post-insert), NOT the payload's orderId. The consumer writes the
 * {@code order_id_map} on every branch that produces a persisted Order row: success,
 * FAILED (row exists with FAILED status), and DupKey-skip (existing order's PK).
 *
 * <h3>At-least-once delivery</h3>
 * XACK is deferred to a {@link TransactionSynchronization#afterCommit()} hook registered
 * at the very start of {@link #process}. If the consumer crashes before the DB transaction
 * commits, no XACK happens, and the message remains in the consumer's Pending Entries
 * List (PEL) for redelivery. On redelivery, the idempotency-key UNIQUE constraint causes
 * the Order insert to throw {@link DataIntegrityViolationException}; the consumer calls
 * {@code selectByIdempotencyKey} to confirm it's a genuine idempotency dup → ack-and-skip
 * (no double order, no double decrement). If the lookup returns null, the violation was
 * something else → re-credit Redis + rethrow (message stays in PEL).
 *
 * <h3>Poison-message escape</h3>
 * A malformed/missing-field payload would NPE or fail to parse, livelocking the PEL
 * (XREADGROUP "0" re-delivers every 500ms). The JSON-parsing phase catches parse/NPE
 * failures, XACKs the message terminal (it's unprocessable), sets status to "failed",
 * and returns without entering the @Transactional DB-write path.
 *
 * <h3>Self-invocation note</h3>
 * {@link #drain} calls {@link #process} via a {@code @Lazy} self-injected reference so the
 * call goes through the Spring proxy — {@code @Transactional} on {@code process} only
 * activates on proxied calls (a direct {@code process(rec)} would bypass the proxy and
 * the transaction would not start, causing {@code TransactionSynchronizationManager} to
 * fail). The self-reference is {@code @Lazy} to break the circular dependency at startup.
 *
 * <h3>Stalled-consumer recovery (Redis 5.0)</h3>
 * Redis 5.0.14.1 has no {@code XAUTOCLAIM} (6.2+). The V1 approach: {@link #drain} does a
 * <strong>second</strong> {@code XREADGROUP} with ID {@code 0}, which re-delivers THIS
 * consumer's own pending entries — reprocessing anything it crashed on mid-flight. A
 * separate {@link #reclaimStalled()} does {@code XPENDING} + {@code XCLAIM} for
 * cross-consumer recovery (a consumer that died permanently).
 */
@Service
@Slf4j
public class StreamConsumerService {

    private static final String STREAM = "orders:pending";
    private static final String GROUP = "order-consumers";
    private static final String CONSUMER = "c1";

    private final StringRedisTemplate redis;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final StockGateService stockGateService;
    private final CoinService coinService;
    private final ProductService productService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Thread pool for parallel order processing — bounded by HikariCP pool (max 50).
    private final java.util.concurrent.ExecutorService consumerPool =
            java.util.concurrent.Executors.newFixedThreadPool(8);

    // Tracks the last-read stream ID for plain XREAD (no consumer group).
    private final java.util.concurrent.atomic.AtomicReference<String> lastReadId = new java.util.concurrent.atomic.AtomicReference<>();

    // Self-injected (lazily) so drain() can call process() through the Spring proxy,
    // activating @Transactional. A direct self-call bypasses the proxy.
    private StreamConsumerService self;

    @jakarta.annotation.PreDestroy
    void shutdown() {
        consumerPool.shutdown();
        try {
            if (!consumerPool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                consumerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            consumerPool.shutdownNow();
        }
    }

    public StreamConsumerService(StringRedisTemplate redis,
                                  OrderMapper orderMapper,
                                  OrderItemMapper orderItemMapper,
                                  ProductMapper productMapper,
                                  StockGateService stockGateService,
                                  CoinService coinService,
                                  ProductService productService) {
        this.redis = redis;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.stockGateService = stockGateService;
        this.coinService = coinService;
        this.productService = productService;
    }

    @Autowired
    void setSelf(@Lazy StreamConsumerService self) {
        this.self = self;
    }

    /**
     * At startup, delete any leftover Stream from the previous Stream-based implementation
     * so the key can be used as a List (Redis doesn't allow type changes on an existing key).
     */
    @PostConstruct
    void cleanupLegacyStream() {
        try {
            redis.delete(STREAM);
            log.info("cleaned up legacy orders:pending key (was Stream, now List)");
        } catch (Exception e) {
            log.debug("cleanup of legacy stream: {}", e.getMessage());
        }
    }

    /**
     * Drain new messages from the stream in a tight loop until empty.
     *
     * <p>Each tick reads ALL available new messages (up to COUNT 100 per XREADGROUP call)
     * in a loop, processing each batch concurrently (via the consumerPool), before reading
     * the next. This drains all entries within one 500ms tick, even under burst load.
     *
     * <p><strong>No in-cycle self-PEL re-read ({@code XREADGROUP ... 0}).</strong>
     * Stalled-recovery is handled by {@link #reclaimStalled()} ({@code XPENDING}+{@code XCLAIM}).
     */
    @Scheduled(fixedDelay = 100)
    public void drain() {
        try {
            // RPOP from the orders:pending List (no Stream, no consumer group, no PEL).
            // Each RPOP returns a single raw string payload — no MapRecord deserialization
            // scrambling. The consumer processes it in a @Transactional DB write.
            // At-least-once: if the consumer crashes after RPOP but before processing,
            // the message is lost (RPOP is destructive). For V1 single-consumer this is
            // acceptable; a production system would use BRPOPLPUSH to a processing list.
            List<String> payloads = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String payload = redis.opsForList().rightPop("orders:pending");
                if (payload == null) break;
                payloads.add(payload);
            }
            if (payloads.isEmpty()) return;
            // Process each payload
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String payload : payloads) {
                futures.add(consumerPool.submit(() -> {
                    try {
                        self.processPayload(payload);
                    } catch (Exception e) {
                        log.error("processBatch: FAILED payload error={}", e.getMessage(), e);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("drain: unexpected error", e);
        }
    }

    /**
     * Durable-write a single order from a raw JSON payload (popped from the orders:pending List).
     *
     * <p>Phase 1: JSON parse (poison-message escape — unparseable payloads are logged + skipped).
     * Phase 2: DB durable write (@Transactional): insert Order (idempotency-guarded, DB auto-gen PK)
     * → DB-backstop decrement per item → insert OrderItems → set Redis status keys.
     */
    @Transactional
    public void processPayload(String payload) {
        Long payloadOrderId;
        Long userId;
        String idempotencyKey;
        int totalInCents;
        String customerName;
        String customerEmail;
        String customerPhone;
        String paymentMethod;
        String shippingAddress;
        JsonNode itemsNode;
        boolean useCoins;
        int coinAmount;

        try {
            JsonNode node = objectMapper.readTree(payload);
            payloadOrderId = node.get("orderId").asLong();
            userId = node.get("userId").asLong();
            idempotencyKey = node.get("idempotencyKey").asText();
            totalInCents = node.get("totalInCents").asInt();
            customerName = node.get("customerName").asText();
            customerEmail = node.get("customerEmail").asText();
            // customerPhone / paymentMethod are Phase C additions — tolerate their absence
            // (a stale payload queued before the fields existed) by null-checking the node.
            JsonNode phoneNode = node.get("customerPhone");
            customerPhone = phoneNode != null && !phoneNode.isNull() ? phoneNode.asText() : null;
            JsonNode payNode = node.get("paymentMethod");
            paymentMethod = payNode != null && !payNode.isNull() ? payNode.asText() : null;
            shippingAddress = node.get("shippingAddress").asText();
            // UC005: useCoins/coinAmount are Phase E additions — tolerate their absence
            // (a payload queued before the coin branch existed) by null-checking the node.
            JsonNode useCoinsNode = node.get("useCoins");
            useCoins = useCoinsNode != null && !useCoinsNode.isNull() && useCoinsNode.asBoolean();
            JsonNode coinAmountNode = node.get("coinAmount");
            coinAmount = coinAmountNode != null && !coinAmountNode.isNull() ? coinAmountNode.asInt() : 0;
            itemsNode = node.get("items");
        } catch (Exception parseEx) {
            log.error("poison payload: unparseable, skipping", parseEx);
            return;
        }

        try {
            Order order = new Order();
            order.setUserId(userId);
            order.setCustomerName(customerName);
            order.setCustomerEmail(customerEmail);
            order.setCustomerPhone(customerPhone);
            if (paymentMethod != null) {
                order.setPaymentMethod(PaymentMethod.valueOf(paymentMethod));
            }
            order.setShippingAddress(shippingAddress);
            order.setTotalInCents(totalInCents);
            order.setStatus(OrderStatus.PENDING);
            order.setIdempotencyKey(idempotencyKey);
            // UC005: record the post-discount paid amount on the row BEFORE insert (atomic with the
            // order row). The consumer's @Transactional coin deduction runs AFTER the stock backstop
            // succeeds, so a FAILED order (coins never spent) still persists paidInCents — but FAILED
            // orders are never paid, so paidInCents is informational there. Coins map 1:1 to cents
            // (100 coins = ¥1 = 100 cents — see CoinServiceImpl.COINS_PER_YUAN + awardReviewReward's
            // paidInCents/100), so the discount in cents equals the coin amount directly.
            int coinDiscountCents = (useCoins && coinAmount > 0) ? coinAmount : 0;
            int paidInCents = totalInCents - coinDiscountCents;
            order.setPaidInCents(paidInCents);

            try {
                orderMapper.insert(order);
            } catch (DataIntegrityViolationException dup) {
                Order existing = orderMapper.selectByIdempotencyKey(idempotencyKey);
                if (existing != null) {
                    redis.opsForValue().set("order_id_map:" + payloadOrderId,
                            String.valueOf(existing.getId()));
                    redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
                    return;
                }
                for (JsonNode item : itemsNode) {
                    Long pid = item.get("productId").asLong();
                    int qty = item.get("quantity").asInt();
                    stockGateService.recredit(pid, qty);
                }
                invalidateListCacheSafe();
                throw dup;
            }

            for (JsonNode item : itemsNode) {
                Long pid = item.get("productId").asLong();
                int qty = item.get("quantity").asInt();
                int rows = productMapper.decrementStock(pid, qty);
                if (rows == 0) {
                    order.setStatus(OrderStatus.FAILED);
                    orderMapper.updateById(order);
                    redis.opsForValue().set("order_id_map:" + payloadOrderId,
                            String.valueOf(order.getId()));
                    stockGateService.recredit(pid, qty);
                    redis.delete("seckill:" + pid + ":" + userId);
                    redis.opsForValue().set("order_status:" + payloadOrderId, "failed");
                    invalidateListCacheSafe();
                    return;
                }
                Product p = productMapper.selectById(pid);
                OrderItem oi = new OrderItem();
                oi.setOrderId(order.getId());
                oi.setProductId(pid);
                oi.setName(p.getName());
                oi.setPriceInCents(p.getPriceInCents());
                oi.setQuantity(qty);
                orderItemMapper.insert(oi);
                redis.opsForValue().set("seckill:" + pid + ":" + userId, "1",
                        3600, TimeUnit.SECONDS);
            }
            // The per-item decrementStock loop above mutated product.stock in the DB; clear the
            // list cache so the list path serves fresh stock (the cache would otherwise be stale ≤30s).
            invalidateListCacheSafe();

            // UC005: deduct coins atomically with the order (only if the order will actually exist —
            // the stock backstop above already succeeded, so the order is durable). spendAtCheckout
            // runs FIFO inside this @Transactional method; on a narrow insufficient-coins race it
            // throws BusinessException(CONFLICT). That rolls back the consumer tx (no Order row, no
            // OrderItems, no DB-stock decrement) AND we must recredit the Redis stock the hot path
            // Lua-gated + delete the per-item seckill keys the success-loop set — mirroring the
            // existing stock-failure FAILED path above (recredit + seckill delete per item).
            //
            // PRE-CHECK (Finding 2 fix): a @Transactional REQUIRED method throwing a RuntimeException
            // marks the shared transaction rollback-only BEFORE the catch block runs, so the catch's
            // FAILED-status write + Redis recredits roll back too (UnexpectedRollbackException) and the
            // RPOP'd payload is lost. To avoid this in the COMMON insufficient-balance case (not a race),
            // pre-check via canSpend (a read — no tx marking) BEFORE calling spendAtCheckout. If
            // canSpend is false, set FAILED + recredit + return WITHOUT calling spendAtCheckout (no
            // exception, no rollback-only mark). spendAtCheckout's CONFLICT throw remains as the
            // last-line guard for the narrow race between canSpend and the spend (documented V1
            // limitation — spec §4c).
            if (useCoins && coinAmount > 0) {
                if (!coinService.canSpend(userId, coinAmount)) {
                    // insufficient spendable coins (common case, not a race) — fail the order,
                    // recredit DB + Redis stock, delete the per-item seckill keys set during the
                    // success loop above. NO spendAtCheckout call → no exception → no rollback-only.
                    order.setStatus(OrderStatus.FAILED);
                    orderMapper.updateById(order);
                    for (JsonNode item : itemsNode) {
                        Long pid = item.get("productId").asLong();
                        int qty = item.get("quantity").asInt();
                        stockGateService.recredit(pid, qty);
                        redis.delete("seckill:" + pid + ":" + userId);
                    }
                    redis.opsForValue().set("order_id_map:" + payloadOrderId,
                            String.valueOf(order.getId()));
                    redis.opsForValue().set("order_status:" + payloadOrderId, "failed");
                    invalidateListCacheSafe();
                    return;
                }
                try {
                    coinService.spendAtCheckout(userId, order.getId(), coinAmount);
                } catch (com.iflytek.frame.exception.BusinessException coinFail) {
                    if (coinFail.getCode() == com.iflytek.frame.common.ResultCode.CONFLICT.getCode()) {
                        // narrow race (canSpend passed but a concurrent spend won the FOR UPDATE lock
                        // first) — the rollback-only behavior persists here (documented V1 limitation).
                        // Fail the order, recredit DB + Redis stock, delete the per-item seckill keys.
                        order.setStatus(OrderStatus.FAILED);
                        orderMapper.updateById(order);
                        for (JsonNode item : itemsNode) {
                            Long pid = item.get("productId").asLong();
                            int qty = item.get("quantity").asInt();
                            stockGateService.recredit(pid, qty);
                            redis.delete("seckill:" + pid + ":" + userId);
                        }
                        redis.opsForValue().set("order_id_map:" + payloadOrderId,
                                String.valueOf(order.getId()));
                        redis.opsForValue().set("order_status:" + payloadOrderId, "failed");
                        invalidateListCacheSafe();
                        return;
                    }
                    throw coinFail;
                }
            }
            redis.opsForValue().set("order_id_map:" + payloadOrderId,
                    String.valueOf(order.getId()));
            redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
        } catch (DataIntegrityViolationException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clear the product list cache after the consumer's DB-backstop stock paths
     * ({@code decrementStock} on success, {@code recredit} on FAILED/rollback) mutate
     * {@code product.stock} without going through {@code ProductService}'s own methods.
     * Redis down is non-fatal (the cache just serves stale data for ≤30s, then self-heals on TTL).
     */
    private void invalidateListCacheSafe() {
        try {
            productService.invalidateListCache();
        } catch (Exception e) {
            log.warn("list-cache invalidation after stock mutation failed (non-fatal): {}", e.getMessage());
        }
    }

}
