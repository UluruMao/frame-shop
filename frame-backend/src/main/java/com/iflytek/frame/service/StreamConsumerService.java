package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.DistributedLock;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PaymentMethod;
import com.iflytek.frame.common.Money;
import com.iflytek.frame.common.RedisKeys;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * XACK happens immediately after {@code processPayload} returns normally inside
 * {@link #process}. If {@code processPayload} throws before the DB transaction commits, the
 * {@code @Transactional} proxy rolls back and the exception propagates to {@code drain}'s
 * per-record catch — no XACK happens, and the message remains in the consumer's Pending
 * Entries List (PEL) for redelivery. On redelivery, the idempotency-key UNIQUE constraint
 * causes the Order insert to throw {@link DataIntegrityViolationException}; the consumer calls
 * {@code selectByIdempotencyKey} to confirm it's a genuine idempotency dup → ack-and-skip
 * (no double order, no double decrement). If the lookup returns null, the violation was
 * something else → re-credit Redis + rethrow (message stays in PEL). The brief window
 * between tx-commit and XACK is covered by the same idempotency key on redelivery.
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
 * the transaction would not start). The self-reference is {@code @Lazy} to break the
 * circular dependency at startup.
 *
 * <h3>Stalled-consumer recovery (Redis 5.0.14.1)</h3>
 * The dev Redis is 5.0.14.1 — no {@code XAUTOCLAIM} (6.2+). The fallback: a leader-guarded
 * {@link #reclaimStalled()} sweep runs {@code XPENDING} to list the group's pending entries,
 * then {@code XCLAIM}s the ones idle &gt; {@link #STALE_IDLE} (60s) into THIS instance's
 * consumer name's PEL and processes them inline. The leader lock ({@link DistributedLock})
 * ensures only one instance does the sweep per tick. A message stuck in a consumer's PEL
 * (because that consumer crashed) is thus re-claimed and reprocessed by the sweeper.
 */
@Service
@Slf4j
public class StreamConsumerService {

    private static final String STREAM = RedisKeys.ordersPending();
    private static final String GROUP = "order-consumers";
    /** Reclaim messages idle > 60s from dead/stalled consumers. */
    private static final Duration STALE_IDLE = Duration.ofSeconds(60);

    /**
     * Per-instance stable consumer name. Read once at class-load from the {@code HOSTNAME}
     * env var (set by the container runtime); falls back to a UUID stable for the JVM's
     * lifetime — same pattern as {@link com.iflytek.frame.service.OrderLifecycleScheduler}
     * and {@link com.iflytek.frame.service.impl.StockGateServiceImpl}. Each instance drains
     * its OWN PEL under its own consumer name (multi-consumer by design — no leader guard on
     * the main drain); only the {@link #reclaimStalled()} sweep is leader-guarded.
     */
    private static final String INSTANCE_ID =
            System.getenv().getOrDefault("HOSTNAME", "instance-" + UUID.randomUUID());
    private static final String CONSUMER = "consumer:" + INSTANCE_ID;

    private final StringRedisTemplate redis;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final StockGateService stockGateService;
    private final CoinService coinService;
    private final ProductService productService;
    private final DistributedLock lock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Thread pool for parallel order processing — bounded by HikariCP pool (max 50).
    private final java.util.concurrent.ExecutorService consumerPool =
            java.util.concurrent.Executors.newFixedThreadPool(8);

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
                                  ProductService productService,
                                  DistributedLock lock) {
        this.redis = redis;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.stockGateService = stockGateService;
        this.coinService = coinService;
        this.productService = productService;
        this.lock = lock;
    }

    @Autowired
    void setSelf(@Lazy StreamConsumerService self) {
        this.self = self;
    }

    /**
     * At startup, create the consumer group on the {@code orders:pending} stream
     * ({@code XGROUP CREATE orders:pending order-consumers $ MKSTREAM}). {@code $} makes the
     * group track only messages XADD'd AFTER group creation; {@code MKSTREAM} creates the
     * stream if absent. Idempotent: a BUSYGROUP (group already exists) is caught + logged —
     * safe across restarts and across all N instances booting concurrently.
     */
    @PostConstruct
    void createConsumerGroup() {
        try {
            redis.opsForStream().createGroup(STREAM, ReadOffset.latest(), GROUP);
            log.info("created consumer group {} on stream {} (idempotent)", GROUP, STREAM);
        } catch (org.springframework.data.redis.RedisSystemException e) {
            // BUSYGROUP: the group already exists (a prior boot or a concurrent instance created it).
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                log.info("consumer group {} already exists on stream {} — reuse", GROUP, STREAM);
            } else {
                log.warn("createGroup unexpected: {}", msg);
            }
        }
    }

    /**
     * Drain the stream's consumer-group backlog — two-phase to work around a Lettuce 7.5.2
     * bug where {@code XREADGROUP ... >} (COUNT 100) delivers ALL matching messages to the
     * consumer's Pending Entries List (PEL) but returns only 1 record to the Java caller.
     *
     * <p><b>Phase 1 — new messages ({@code >}):</b> {@code XREADGROUP GROUP order-consumers
     * {consumerName} COUNT 100 STREAMS orders:pending >}. This enters all never-delivered
     * messages into the PEL (making them durable) but returns only 1 record. That 1 record is
     * submitted to the consumerPool for {@link #process(MapRecord)}.
     *
     * <p><b>Phase 2 — pending entries (XPENDING + XRANGE):</b> the remaining PEL entries
     * (delivered to the PEL by phase 1 but never returned to Java) are listed via XPENDING
     * (min/max ID) and their content read via XRANGE in a single call. This avoids the
     * re-delivery loop of {@code XREADGROUP ... 0} (which re-delivers ALL pending entries on
     * every call, including in-flight ones, causing an infinite spin). Each pending entry is
     * read exactly once per tick; the idempotency-key pre-check in {@link #processPayload}
     * handles the dup when a pending entry is re-read on the next tick while its prior
     * {@code process()} is still in-flight (the second call ack-and-skips).
     *
     * <p>Poison payloads (JSON parse fail) are XACKed terminal (escape the PEL). Each record
     * is processed via the consumerPool; {@code process()} runs the {@code @Transactional}
     * durable write and XACKs after {@code processPayload} returns normally — so a crash-before-
     * commit leaves the message in the PEL for the next tick's phase 2 to re-read.
     *
     * <p><b>Not leader-guarded:</b> each instance drains its own backlog under its own consumer
     * name (multi-consumer by design). Only {@link #reclaimStalled()} is leader-guarded.
     */
    @Scheduled(fixedDelay = 100)
    public void drain() {
        try {
            // Two-phase drain (see class javadoc for the Lettuce 7.5.2 bug rationale).
            int totalRead = 0;
            // Phase 1: new messages (`>`) — enters all into PEL, returns 1.
            List<MapRecord<String, String, String>> newRecords = readNewMessages();
            if (newRecords != null && !newRecords.isEmpty()) {
                totalRead += newRecords.size();
                for (MapRecord<String, String, String> rec : newRecords) {
                    consumerPool.submit(() -> {
                        try { self.process(rec); } catch (Exception e) { log.error("process record {} FAILED error={}", rec.getId(), e.getMessage(), e); }
                    });
                }
            }
            // Phase 2: pending entries via XPENDING + XRANGE — reads PEL entries delivered but
            // never returned by phase 1's XREADGROUP. Each entry is read once per tick; the
            // idempotency key dedups any still in-flight from a prior tick.
            List<MapRecord<String, String, String>> pendingRecords = readPendingViaXrange();
            if (pendingRecords != null && !pendingRecords.isEmpty()) {
                totalRead += pendingRecords.size();
                for (MapRecord<String, String, String> rec : pendingRecords) {
                    consumerPool.submit(() -> {
                        try { self.process(rec); } catch (Exception e) { log.error("process record {} FAILED error={}", rec.getId(), e.getMessage(), e); }
                    });
                }
            }
            if (totalRead > 0) {
                log.debug("drain: processed {} records this tick", totalRead);
            }
        } catch (Exception e) {
            log.error("drain: unexpected error", e);
        }
    }

    /**
     * Read pending PEL entries via XPENDING (list IDs) + XRANGE (read content). This bypasses
     * the Lettuce XREADGROUP bug where the {@code >} offset delivers all messages to the PEL
     * but returns only 1 to the caller. The remaining PEL entries are listed via XPENDING and
     * their content read via XRANGE — each entry is read exactly once per tick (no re-delivery
     * loop like {@code XREADGROUP ... 0}). The entries are NOT re-claimed (they're already in
     * THIS consumer's PEL from the phase 1 {@code >} call), so no XCLAIM is needed.
     */
    private List<MapRecord<String, String, String>> readPendingViaXrange() {
        try {
            // XPENDING: list this group's pending entries (ID range, count limit).
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                    redis.opsForStream().pending(STREAM, GROUP);
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return java.util.Collections.emptyList();
            }
            // Get the min and max pending IDs — use XRANGE to read all entries in that range.
            String minId = summary.minMessageId() != null ? summary.minMessageId() : "-";
            String maxId = summary.maxMessageId() != null ? summary.maxMessageId() : "+";
            // XRANGE: read all stream entries from min to max pending ID in one call.
            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> entries =
                    redis.<String, String>opsForStream().range(STREAM,
                            org.springframework.data.domain.Range.closed(minId, maxId));
            if (entries == null || entries.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            return entries;
        } catch (Exception e) {
            log.debug("readPendingViaXrange: failed (non-fatal): {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Read new stream messages via {@code XREADGROUP ... >} (COUNT 100). Returns only 1 record
     * per call due to a Lettuce 7.5.2 bug (the command delivers ALL matching messages to the
     * PEL but returns only 1 to the caller). This is acceptable because phase 2
     * ({@link #readPendingViaXrange}) reads the remaining PEL entries via XPENDING + XRANGE.
     */
    @SuppressWarnings("unchecked")
    private List<MapRecord<String, String, String>> readNewMessages() {
        List<MapRecord<String, String, String>> records =
                redis.<String, String>opsForStream().read(
                        Consumer.from(GROUP, CONSUMER),
                        StreamReadOptions.empty().count(100),
                        StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        return records != null ? records : java.util.Collections.emptyList();
    }

    /**
     * Process a single stream record: parse → durable write (delegating to {@link #processPayload})
     * → XACK. Poison payloads (unparseable) are XACKed terminal immediately so they don't
     * livelock the PEL. The {@code @Transactional} durable write is atomic: if {@code processPayload}
     * returns normally, the Order + OrderItems + stock decrement all committed (or the dup-key /
     * stock-failure recovery branches ran and set the Redis status keys).
     *
     * <p><b>XACK via afterCommit hook:</b> XACK is registered as a
     * {@link org.springframework.transaction.support.TransactionSynchronization#afterCommit()}
     * callback BEFORE {@code processPayload} runs. The proxy commits the tx on method return;
     * if commit succeeds, {@code afterCommit} fires → XACK. If commit fails (connection lost,
     * deadlock victim, a constraint surfacing at commit) or {@code processPayload} throws, the tx
     * rolls back, {@code afterCommit} never fires → NO XACK → the message stays in the PEL for
     * {@code reclaimStalled()} to XCLAIM + reprocess. This closes the lost-message window where
     * XACK runs inside the still-uncommitted tx body and a subsequent commit failure orphans the
     * message (ACKed but DB rolled back).
     *
     * <p><b>Dup-key path:</b> the {@code selectByIdempotencyKey} pre-check bypasses the
     * {@code DataIntegrityViolationException} throw for the common redelivery case (order already
     * exists → ack-and-skip without attempting the insert), so the tx is NOT marked rollback-only
     * and {@code afterCommit} fires normally. The rare concurrent-race catch (pre-check missed it,
     * two redeliveries passed simultaneously) DOES mark the tx rollback-only → {@code afterCommit}
     * never fires → message stays in PEL → {@code reclaimStalled} retries after 60s → the pre-check
     * then ack-and-skips. This is the desired behavior (no livelock).
     *
     * <p>Self-invoked through the {@code @Lazy} proxy so {@code @Transactional} activates.
     *
     * <p><b>READ_COMMITTED isolation:</b> the consumer processes many records for the same
     * product concurrently (8 pool threads). Under the default REPEATABLE READ, the tx's first
     * SELECT (the idempotency pre-check) establishes a snapshot; a concurrent tx that commits
     * a product-stock decrement between this tx's snapshot and its {@code decrementStock} UPDATE
     * triggers MariaDB error 1020 ("Record has changed since last read"). READ_COMMITTED gives
     * each statement a fresh snapshot, so the UPDATE sees the latest committed row and no 1020
     * fires. The {@code WHERE stock >= qty} clause remains the oversell guard (atomic decrement).
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void process(MapRecord<String, String, String> rec) {
        RecordId messageId = rec.getId();
        String payload = rec.getValue().get("payload");
        // Phase 1: poison-message escape (outside the @Transactional DB-write path).
        if (payload == null || !isParseable(payload)) {
            log.error("poison payload (record {}): unparseable, XACK terminal", messageId);
            redis.opsForStream().acknowledge(STREAM, GROUP, messageId);
            return;
        }
        // Phase 2: @Transactional durable write. If this returns normally, the @Transactional
        // proxy will commit on method return. XACK must fire ONLY AFTER the commit succeeds —
        // if XACK runs inside the still-uncommitted tx body and the proxy's commit then fails
        // (connection lost, deadlock victim, a constraint surfacing at commit), the message is
        // already ACKed (removed from the PEL) while the DB tx rolls back → the order is lost
        // with no redelivery. The afterCommit hook closes this window:
        //   - Success: commit succeeds → afterCommit fires → XACK. Safe.
        //   - Crash/throw before commit: tx rolls back, afterCommit never fires → NO XACK →
        //     message stays in PEL → redelivered. Correct at-least-once.
        //   - Rare concurrent-race dup-key catch (pre-check missed it): tx is rollback-only,
        //     afterCommit never fires → NO XACK → message stays in PEL → reclaimStalled
        //     re-XCLAIMs + reprocesses (the pre-check then ack-and-skips it). Desired behavior.
        //
        // The afterCommit hook is registered BEFORE processPayload runs so it's registered
        // regardless of what processPayload does (if processPayload throws, the tx rolls back
        // and afterCommit never fires — correct). messageId is captured as an effectively-final
        // local for the lambda.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            redis.opsForStream().acknowledge(STREAM, GROUP, messageId);
                        }
                    });
        }
        processPayload(payload);
        // If a real tx is active, XACK is deferred to the afterCommit hook (registered above) —
        // it fires after the proxy commits. If no tx is active (e.g. mock tests without a Spring
        // tx manager), XACK directly here (processPayload returning normally is the "commit"
        // signal in that context). This preserves the at-least-once contract in both paths.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            redis.opsForStream().acknowledge(STREAM, GROUP, messageId);
        }
    }

    /** True iff the payload is well-formed JSON (poison-escape gate, pre-@Transactional). */
    private boolean isParseable(String payload) {
        try {
            objectMapper.readTree(payload);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Leader-guarded periodic sweep that reclaims messages idle &gt; {@link #STALE_IDLE} (60s)
     * from dead/stalled consumers via {@code XPENDING} + {@code XCLAIM} (the dev Redis is
     * 5.0.14.1 — no {@code XAUTOCLAIM} until 6.2+, so the XPENDING+XCLAIM fallback is used).
     * Only one instance runs this per tick (the {@link DistributedLock} leader).
     *
     * <p>The 60s idle threshold is the key: it's well beyond any in-flight {@code @Transactional}
     * processing (which commits in ms), so XCLAIM only grabs genuinely-stalled entries — a
     * consumer that crashed mid-flight (message read but never XACK'd because its tx never
     * committed) or a permanently-dead consumer's backlog. The claimed entries are then processed
     * via the consumerPool through the normal {@code @Transactional} + XACK path; the
     * idempotency-key UNIQUE constraint dedups any that already committed before the crash.
     *
     * <p>Drain()'s phase 2 reads the PEL via XPENDING+XRANGE every tick (recovering entries
     * that Lettuce delivered to the PEL but never returned to Java); this sweep is the
     * cross-consumer crash-recovery path for entries owned by a dead consumer.
     */
    @Scheduled(fixedDelay = 10_000)
    public void reclaimStalled() {
        if (!lock.tryLock("shedlock:reclaimStalledOrders", INSTANCE_ID, Duration.ofSeconds(30))) {
            return; // another instance is the leader for this sweep tick
        }
        try {
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary;
            try {
                summary = redis.opsForStream().pending(STREAM, GROUP);
            } catch (Exception e) {
                log.debug("reclaimStalled: pending() failed (stream/group absent?): {}", e.getMessage());
                return;
            }
            if (summary == null || summary.getTotalPendingMessages() == 0) return;

            org.springframework.data.redis.connection.stream.PendingMessages pending =
                    redis.opsForStream().pending(STREAM, GROUP,
                            org.springframework.data.domain.Range.unbounded(), 100L);
            if (pending == null || pending.isEmpty()) return;

            java.util.List<RecordId> stale = new ArrayList<>();
            for (org.springframework.data.redis.connection.stream.PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().compareTo(STALE_IDLE) >= 0) {
                    stale.add(pm.getId());
                }
            }
            if (stale.isEmpty()) return;

            // XCLAIM the stale entries to THIS consumer (they enter this consumer's PEL), then
            // process them through the consumerPool (the normal @Transactional path). The 60s
            // idle threshold ensures these aren't mid-flight in drain()'s pool — XCLAIM only grabs
            // entries a consumer gave up on (crashed/dead).
            @SuppressWarnings("unchecked")
            List<MapRecord<String, String, String>> claimed = redis.<String, String>opsForStream().claim(
                    STREAM, GROUP, CONSUMER, STALE_IDLE,
                    stale.toArray(new RecordId[0]));
            if (claimed == null || claimed.isEmpty()) return;
            log.info("reclaimStalled: reclaimed {} stalled messages into consumer {}", claimed.size(), CONSUMER);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (MapRecord<String, String, String> rec : claimed) {
                futures.add(consumerPool.submit(() -> {
                    try {
                        self.process(rec);
                    } catch (Exception e) {
                        log.error("reclaimStalled: process reclaimed {} FAILED error={}", rec.getId(), e.getMessage(), e);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("reclaimStalled: unexpected error", e);
        } finally {
            lock.unlock("shedlock:reclaimStalledOrders", INSTANCE_ID);
        }
    }

    /**
     * Durable-write a single order from a raw JSON payload (popped from the orders:pending List).
     *
     * <p>Phase 1: JSON parse (poison-message escape — unparseable payloads are logged + skipped).
     * Phase 2: DB durable write (@Transactional): insert Order (idempotency-guarded, DB auto-gen PK)
     * → DB-backstop decrement per item → insert OrderItems → set Redis status keys.
     */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
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
        java.util.Set<Long> liveFlashIds = new java.util.HashSet<>();

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
            // liveFlashIds: which items were live-flash at checkout time (got the discount +
            // the Redis gate). Tolerate absence for payloads queued before this field existed
            // — older payloads treated every flash item as live-flash, so default to that.
            JsonNode liveFlashNode = node.get("liveFlashIds");
            if (liveFlashNode != null && liveFlashNode.isArray()) {
                for (JsonNode lf : liveFlashNode) liveFlashIds.add(lf.asLong());
            }
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
            // (100 coins = ¥1 = 100 cents — see Money.COINS_PER_YUAN + awardReviewReward's
            // paidInCents/100), so the discount in cents equals the coin amount directly.
            int coinDiscountCents = (useCoins && coinAmount > 0) ? Money.coinsToCents(coinAmount) : 0;
            int paidInCents = totalInCents - coinDiscountCents;
            order.setPaidInCents(paidInCents);

            // PRE-CHECK the idempotency key BEFORE attempting the insert (Finding 2 fix for the
            // consumer path). A redelivered message's order already exists in the DB — checking
            // first avoids the orderMapper.insert throwing DataIntegrityViolationException, which
            // (per Finding 2) marks the shared @Transactional tx rollback-only BEFORE the catch
            // block runs. A rollback-only tx means process() can't commit → process()'s XACK line
            // never runs (UnexpectedRollbackException propagates to drain's catch) → the message
            // is orphaned in the PEL and reclaimStalled livelocks re-XCLAIMing it. The pre-check
            // sidesteps this for the COMMON redelivery case (sequential reprocessing of a prior-
            // committed order): no insert attempt → no exception → tx commits → XACK fires. The
            // catch block below remains as the fallback for the rare concurrent-race case (two
            // redeliveries pass the pre-check simultaneously, one wins the insert, the other
            // hits the dup-key — that one message may still poison the tx, but reclaimStalled
            // will retry it after 60s and the pre-check then succeeds).
            Order existing = orderMapper.selectByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                        String.valueOf(existing.getId()));
                redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "pending");
                return;
            }

            try {
                orderMapper.insert(order);
            } catch (DataIntegrityViolationException dup) {
                // Rare concurrent-race fallback: two redeliveries passed the pre-check above
                // simultaneously; one won the insert, this one hit the dup-key. Re-select to
                // confirm it's a genuine idempotency dup → ack-and-skip (the tx may be poisoned
                // rollback-only by this exception, in which case process()'s commit will throw
                // UnexpectedRollbackException and the message stays in the PEL for a reclaimStalled
                // retry — where the pre-check above will succeed).
                Order existing2 = orderMapper.selectByIdempotencyKey(idempotencyKey);
                if (existing2 != null) {
                    redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                            String.valueOf(existing2.getId()));
                    redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "pending");
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
                    redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                            String.valueOf(order.getId()));
                    // Only live-flash items had Redis stock decremented by the Lua gate;
                    // recredit Redis for those (dbOnly items only touched DB, which rolls
                    // back via @Transactional).
                    if (liveFlashIds.contains(pid)) {
                        stockGateService.recredit(pid, qty);
                    }
                    redis.delete(RedisKeys.seckill(pid, userId));
                    redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "failed");
                    invalidateListCacheSafe();
                    return;
                }
                Product p = productMapper.selectById(pid);
                OrderItem oi = new OrderItem();
                oi.setOrderId(order.getId());
                oi.setProductId(pid);
                oi.setName(p.getName());
                // Snapshot the effective price actually paid: discounted only for live-flash
                // items (those got the discount at checkout); non-flash + not-yet-open flash
                // sell at list price. Matches order.total_in_cents (carries the same rule).
                boolean liveFlash = liveFlashIds.contains(pid);
                oi.setPriceInCents(Money.priceInCentsFor(p, liveFlash));
                oi.setQuantity(qty);
                orderItemMapper.insert(oi);
                // One-per-user seckill key applies ONLY to live-flash items (the restricted
                // seckill items); non-flash items are freely re-purchasable.
                if (liveFlash) {
                    redis.opsForValue().set(RedisKeys.seckill(pid, userId), "1",
                            3600, TimeUnit.SECONDS);
                }
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
                        redis.delete(RedisKeys.seckill(pid, userId));
                    }
                    redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                            String.valueOf(order.getId()));
                    redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "failed");
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
                            redis.delete(RedisKeys.seckill(pid, userId));
                        }
                        redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                                String.valueOf(order.getId()));
                        redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "failed");
                        invalidateListCacheSafe();
                        return;
                    }
                    throw coinFail;
                }
            }
            redis.opsForValue().set(RedisKeys.orderIdMap(String.valueOf(payloadOrderId)),
                    String.valueOf(order.getId()));
            redis.opsForValue().set(RedisKeys.orderStatus(payloadOrderId), "pending");
        } catch (DataIntegrityViolationException rethrow) {
            throw rethrow;
        } catch (Exception e) {
            throw new com.iflytek.frame.exception.BusinessException(
                    com.iflytek.frame.common.ResultCode.INTERNAL_ERROR, "serialization failed");
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
