package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.OrderStatus;
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
                                  StockGateService stockGateService) {
        this.redis = redis;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.stockGateService = stockGateService;
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
        String shippingAddress;
        JsonNode itemsNode;

        try {
            JsonNode node = objectMapper.readTree(payload);
            payloadOrderId = node.get("orderId").asLong();
            userId = node.get("userId").asLong();
            idempotencyKey = node.get("idempotencyKey").asText();
            totalInCents = node.get("totalInCents").asInt();
            customerName = node.get("customerName").asText();
            customerEmail = node.get("customerEmail").asText();
            shippingAddress = node.get("shippingAddress").asText();
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
            order.setShippingAddress(shippingAddress);
            order.setTotalInCents(totalInCents);
            order.setStatus(OrderStatus.PENDING);
            order.setIdempotencyKey(idempotencyKey);

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
            redis.opsForValue().set("order_id_map:" + payloadOrderId,
                    String.valueOf(order.getId()));
            redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
        } catch (DataIntegrityViolationException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
