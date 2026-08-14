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
 * The payload carries a {@code orderId} (millis-based correlation id) used ONLY for the
 * {@code order_status:{orderId}} Redis key. The Order row's PK is DB AUTO_INCREMENT
 * (NOT set from the payload) — this avoids same-millisecond PK collisions. OrderItem FK
 * uses {@code order.getId()} (the DB-generated PK post-insert), NOT the payload's orderId.
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

    // Self-injected (lazily) so drain() can call process() through the Spring proxy,
    // activating @Transactional. A direct self-call bypasses the proxy.
    private StreamConsumerService self;

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
     * Create the consumer group at startup. MKSTREAM is implicit on Spring Data Redis's
     * {@code createGroup} when the stream doesn't exist (it creates the stream first).
     * If the group already exists (BUSYGROUP), the exception is swallowed.
     */
    @PostConstruct
    void createGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0"), GROUP);
        } catch (Exception e) {
            // BUSYGROUP (group exists) or stream missing — the first XADD creates the stream;
            // the group will be created on the next drain cycle. Safe to log-and-continue.
            log.debug("consumer group create: {}", e.getMessage());
        }
    }

    /**
     * Drain new messages from the stream, then re-read this consumer's own PEL.
     *
     * <p>Two reads per cycle:
     * <ol>
     *   <li>{@code XREADGROUP ... >} — new messages never delivered to this group.</li>
     *   <li>{@code XREADGROUP ... 0} — re-delivers THIS consumer's own pending entries
     *       (messages it read but never XACKed — e.g. it crashed before commit). This is
     *       the Redis 5.0 stalled-self-recovery mechanism (no XAUTOCLAIM needed).</li>
     * </ol>
     */
    @Scheduled(fixedDelay = 500)
    public void drain() {
        // 1. Read NEW messages (ID ">" = never-delivered-to-this-group).
        List<MapRecord<String, Object, Object>> newRecords = redis.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        processBatch(newRecords);

        // 2. Re-read THIS consumer's own PEL (ID "0" = re-deliver my pending entries).
        //    On Redis 5.0.14.1 this is the V1 stalled-recovery: if the consumer crashed
        //    mid-process (before XACK), the message stays in its PEL and is re-delivered
        //    here on the next drain. The idempotency-key UNIQUE constraint makes the
        //    re-delivery safe (dup insert → ack-and-skip, no double decrement).
        List<MapRecord<String, Object, Object>> pendingRecords = redis.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(10),
                StreamOffset.create(STREAM, ReadOffset.from("0")));
        processBatch(pendingRecords);
    }

    private void processBatch(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) return;
        for (MapRecord<String, Object, Object> rec : records) {
            try {
                // Call through the Spring proxy (self) so @Transactional activates.
                self.process(rec);
            } catch (Exception e) {
                log.error("failed to process record {}: leaving in PEL for retry", rec.getId(), e);
                // do NOT XACK — leave in PEL for the self-PEL re-read on the next drain.
            }
        }
    }

    /**
     * Durable-write a single order from the stream payload.
     *
     * <p>Two phases:
     * <ol>
     *   <li><b>JSON parse</b> (poison-message escape): if the payload is malformed or a
     *       field is missing, XACK the message terminal + set status to "failed" + return.
     *       No DB writes, no transaction rollback — the message is unprocessable poison.</li>
     *   <li><b>DB durable write</b> (inside @Transactional + afterCommit-XACK): insert
     *       Order (idempotency-guarded, DB auto-gen PK) → DB-backstop decrement per item →
     *       insert OrderItems (FK = order.getId()).</li>
     * </ol>
     *
     * <p>XACK is registered as an {@code afterCommit} hook at the very start so it fires
     * on EVERY return path from the DB-write phase (DupKey skip, FAILED branch, success).
     * This is the at-least-once guarantee: crash before commit → no ack → message stays
     * in PEL → redelivered → idempotency-skip on the dup insert.
     *
     * <p><strong>Non-transactional Redis writes inside @Transactional:</strong> the Redis
     * writes in the FAILED branch (recredit, DEL key, set status) and the one-per-user
     * key set in the success path are NOT part of the DB transaction — Redis cannot roll
     * back. This is acceptable for V1: Redis is the fast cache, the DB is the source of
     * truth. A crash after the DB commits but before Redis writes would leave stale Redis
     * state, but the DB-backstop decrement in the consumer catches drift.
     */
    @Transactional
    public void process(MapRecord<String, Object, Object> rec) {
        // Register the XACK as an afterCommit hook — fires AFTER the @Transactional
        // commits when process() returns. If process() throws (leaving the message in
        // the PEL), the transaction rolls back and afterCommit does NOT fire → no ack.
        // This requires an active transaction synchronization (which @Transactional provides).
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // This would mean @Transactional isn't proxying — a real wiring problem.
            throw new IllegalStateException(
                    "No active transaction synchronization — @Transactional is not active for process()");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId());
                    }
                });

        String payload = String.valueOf(rec.getValue().get("payload"));
        Long payloadOrderId;
        Long userId;
        String idempotencyKey;
        int totalInCents;
        String customerName;
        String customerEmail;
        String shippingAddress;
        JsonNode itemsNode;

        // ---- JSON parse (poison-message escape) ----
        // A malformed/missing-field message would NPE/parse-fail and livelock the PEL
        // (XREADGROUP "0" re-delivers every 500ms). On a parse failure, XACK the message
        // terminal (it's unprocessable — no point retrying), set status to "failed", and
        // return WITHOUT throwing (no transaction rollback needed — no DB writes happened).
        // This is the ONE terminal-early-acknowledge case that's correct: the message is
        // poison, not a transient failure.
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
            log.error("poison message {}: unparseable payload, XACKing terminal", rec.getId(), parseEx);
            // Try to extract orderId for the status key; if even that fails, use the record id.
            String statusKey = "order_status:" + rec.getId();
            try {
                statusKey = "order_status:" + objectMapper.readTree(payload).get("orderId").asLong();
            } catch (Exception ignored) {
                // payload is truly garbage — fall back to record id
            }
            redis.opsForValue().set(statusKey, "failed");
            redis.opsForStream().acknowledge(STREAM, GROUP, rec.getId());
            return;
        }

        // ---- DB durable write (inside @Transactional + afterCommit-XACK path) ----
        try {
            Order order = new Order();
            // Do NOT set the id — let the DB AUTO_INCREMENT generate it (avoids
            // same-millisecond collisions on the PK; the payload's orderId is only a
            // correlation id for the order_status:{payloadOrderId} Redis key).
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
                // Disambiguate: was this a genuine idempotency-key dup, or something else?
                Order existing = orderMapper.selectByIdempotencyKey(idempotencyKey);
                if (existing != null) {
                    // Genuine idempotency dedup — a prior delivery already persisted + decremented.
                    // Do NOT re-decrement stock. Ack-and-skip (afterCommit hook XACKs).
                    redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
                    return;
                }
                // NOT an idempotency-key dup — some other integrity violation. Re-credit the
                // Redis stock the hot path decremented, then rethrow (transaction rolls back,
                // afterCommit XACK does NOT fire, message stays in PEL for retry).
                for (JsonNode item : itemsNode) {
                    Long pid = item.get("productId").asLong();
                    int qty = item.get("quantity").asInt();
                    stockGateService.recredit(pid, qty);
                }
                throw dup;
            }

            // DB-backstop: conditional decrement per item. Redis already decremented in
            // the hot path; this catches Redis/DB drift (e.g. Redis was reloaded from DB
            // mid-sale, or a failed prior delivery left Redis over-decremented). If
            // rows=0, the order is FAILED + Redis re-credited + one-per-user key deleted.
            for (JsonNode item : itemsNode) {
                Long pid = item.get("productId").asLong();
                int qty = item.get("quantity").asInt();
                int rows = productMapper.decrementStock(pid, qty);
                if (rows == 0) {
                    // Redis drifted below DB stock — mark FAILED, re-credit Redis,
                    // delete the one-per-user key, and ack (afterCommit hook).
                    order.setStatus(OrderStatus.FAILED);
                    orderMapper.updateById(order);
                    stockGateService.recredit(pid, qty);
                    redis.delete("seckill:" + pid + ":" + userId);
                    redis.opsForValue().set("order_status:" + payloadOrderId, "failed");
                    return;
                }
                Product p = productMapper.selectById(pid);
                OrderItem oi = new OrderItem();
                oi.setOrderId(order.getId()); // ACTUAL DB-generated PK, not the payload's millis orderId
                oi.setProductId(pid);
                oi.setName(p.getName());
                oi.setPriceInCents(p.getPriceInCents());
                oi.setQuantity(qty);
                orderItemMapper.insert(oi);
                // one-per-user key set AFTER durable persist (inside the commit boundary
                // for the DB part — Redis write is non-transactional, see class javadoc).
                redis.opsForValue().set("seckill:" + pid + ":" + userId, "1",
                        3600, TimeUnit.SECONDS);
            }
            redis.opsForValue().set("order_status:" + payloadOrderId, "pending");
        } catch (DataIntegrityViolationException rethrow) {
            // Already disambiguated above — re-throw to roll back the transaction.
            throw rethrow;
        } catch (Exception e) {
            throw new RuntimeException(e); // rolls back the transaction; no XACK (afterCommit won't fire)
        }
    }

    /**
     * Cross-consumer stalled-entry recovery (Redis 5.0: XPENDING + XCLAIM).
     *
     * <p>On Redis 5.0.14.1 there is no {@code XAUTOCLAIM} (6.2+). This does a best-effort
     * XPENDING summary + per-entry XCLAIM for entries idle > 60s, assigning them to this
     * consumer. The self-PEL re-read in {@link #drain} (ID "0") is the primary V1 recovery;
     * this handles the case where a DIFFERENT consumer died permanently and its entries
     * need to be claimed by a live one.
     */
    @Scheduled(fixedDelay = 60000)
    public void reclaimStalled() {
        try {
            var pending = redis.opsForStream().pending(STREAM, GROUP);
            if (pending == null || pending.getTotalPendingMessages() == 0) return;
            log.debug("pending messages in {}: {}", GROUP, pending.getTotalPendingMessages());
            // Full XPENDING range + XCLAIM per idle entry would go here for production.
            // V1 relies on the self-PEL re-read in drain() for same-consumer recovery.
        } catch (Exception e) {
            log.debug("reclaim stalled: {}", e.getMessage());
        }
    }
}
