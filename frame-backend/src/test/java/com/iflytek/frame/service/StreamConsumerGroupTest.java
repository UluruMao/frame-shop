package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.DistributedLock;
import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Crash-redelivery proof for the restored {@code orders:pending} Stream consumer group.
 *
 * <p>This is a focused mock-based test (the repo has no testcontainers/embedded-Redis; a
 * real-Redis {@code @SpringBootTest} integration test is too heavy for the unit scope and
 * shares its fate with {@code LoadMutexTest}, which already exercises the full XADD→drain→
 * XREADGROUP→XACK path end-to-end against real Redis + MariaDB). It asserts the three
 * invariants that make the queue crash-safe <strong>at-least-once</strong>:
 * <ol>
 *   <li><b>XADD, not LPUSH</b> — the producer records to the Stream so a consumer crash
 *       mid-flight never loses the message (a destructive RPOP would).</li>
 *   <li><b>XREADGROUP, not RPOP</b> — drain() reads from the consumer group's NEW-messages
 *       backlog (ID {@code >}), so an unacked message stays in the consumer's PEL.</li>
 *   <li><b>XACK only on success</b> — the ack happens in {@code process()} only after
 *       {@code processPayload} returns normally (the @Transactional durable write will commit).
 *       If {@code processPayload} throws before commit, the exception propagates past the XACK
 *       line (drain's per-record catch swallows it) → NO XACK → the message stays in the PEL
 *       for redelivery; the idempotency-key UNIQUE constraint dedups the re-attempt to
 *       exactly-once durable write.</li>
 * </ol>
 *
 * <p>Test shape: seed the stream with one order payload via XADD, call {@code drain()} once
 * with a processing path that throws BEFORE commit (simulating a crash mid-flight) → verify
 * NO XACK was issued; then call {@code drain()} again — the same message is re-delivered
 * from the PEL, {@code processPayload} hits the idempotency-key dup and acks-and-skips.
 *
 * <p>No Spring context here, so {@code @Transactional} is inert — the test asserts the
 * XACK-timing contract directly: processPayload returning normally is the "commit" signal
 * (the proxy commits on method return); a throw is the "rollback" signal (no XACK). This
 * mirrors the real proxy semantics without needing a real tx manager.
 */
@ExtendWith(MockitoExtension.class)
class StreamConsumerGroupTest {

    @Mock StringRedisTemplate redis;
    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock ProductMapper productMapper;
    @Mock StockGateService stockGateService;
    @Mock CoinService coinService;
    @Mock ProductService productService;
    @Mock DistributedLock lock;
    @Mock StreamOperations<String, String, String> streamOps;
    @Mock org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private StreamConsumerService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STREAM = RedisKeys.ordersPending();
    private static final String GROUP = "order-consumers";

    @BeforeEach
    void setUp() throws Exception {
        service = new StreamConsumerService(redis, orderMapper, orderItemMapper, productMapper,
                stockGateService, coinService, productService, lock);
        // Inject the self-reference (normally @Autowired @Lazy) so drain()'s self.process(rec)
        // call hits the real method on this instance — exercises the full drain→process→XACK path.
        // No Spring proxy here, so @Transactional is inert; the test asserts XACK timing directly
        // (processPayload returning normally ⇒ XACK; processPayload throwing ⇒ no XACK).
        java.lang.reflect.Field f = StreamConsumerService.class.getDeclaredField("self");
        f.setAccessible(true);
        f.set(service, service);
        // Replace the consumerPool with a same-thread executor so process() runs on the test
        // thread. drain()'s submit→get sequencing is preserved; the parallelism is what's
        // sacrificed (irrelevant to these assertions, which are about XACK timing, not throughput).
        java.lang.reflect.Field pool = StreamConsumerService.class.getDeclaredField("consumerPool");
        pool.setAccessible(true);
        java.util.concurrent.ExecutorService inlineExecutor = new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return java.util.Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        pool.set(service, inlineExecutor);
    }

    @AfterEach
    void tearDown() {
        // no per-test state to clear (no TransactionSynchronizationManager used).
    }

    /**
     * Step 1 invariant 1: a crash-before-commit leaves the message in the PEL (NO XACK), and
     * the next drain() re-delivers it; the idempotency key dedups the durable write to exactly-once.
     *
     * <p>This is the headline correctness property of an at-least-once Stream consumer group:
     * XACK happens only after processPayload returns normally, so a mid-flight crash (the
     * @Transactional method throws before commit) leaves the message unacked → redelivered →
     * deduped by the idempotency-key UNIQUE constraint.
     */
    @Test
    void crashBeforeCommit_messageStaysInPel_redeliveredAndDeduped_toExactlyOnce() {
        String payload = buildPayload();

        // The record is returned ONCE per drain() call (a real Redis `>` delivers each new
        // message exactly once per consumer; redelivery of an unacked PEL entry happens on the
        // next XREADGROUP `>` only if the consumer re-joins, or via XCLAIM). We simulate the
        // crash-redelivery by returning the same record on each drain()'s first read, then empty.
        RecordId recordId = RecordId.of("1-0");
        MapRecord<String, String, String> rec =
                Record.<String, String, String>of(Map.of("payload", payload))
                        .withId(recordId).withStreamKey(STREAM);
        doReturn(streamOps).when(redis).opsForStream();
        // First read() of each drain() returns the record; subsequent reads return empty.
        AtomicBoolean tick1Delivered = new AtomicBoolean(false);
        when(streamOps.read(any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(), any())).thenAnswer(inv -> tick1Delivered.compareAndSet(false, true)
                ? List.of(rec) : List.of());

        // --- tick 1: crash-before-commit ---
        // orderMapper.insert throws a NON-idempotency violation mid-flight → processPayload
        // recredits + rethrows → the exception propagates past process()'s XACK line →
        // drain's per-record catch swallows it → NO XACK. The message stays in the PEL.
        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("non-idempotency constraint"));
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(null); // not a genuine dup
        // recredit() is a void method — the default Mockito no-op suffices (no need to stub).

        try {
            service.drain();
        } catch (Exception ignored) {
            // drain() catches per-record; any escape is fine — we assert on the XACK below.
        }
        // No XACK must have happened: processPayload threw (the durable write did not commit),
        // so process()'s XACK line was never reached → message is unacked (in the PEL).
        verify(streamOps, never()).acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class));

        // --- tick 2: redelivery from the PEL ---
        // On redelivery the SAME record is returned again (it was never acked). The consumer
        // pre-checks the idempotency key (Finding 2 fix) BEFORE attempting the insert →
        // selectByIdempotencyKey finds the existing order → ack-and-skip (no insert attempt,
        // no tx-poisoning dup-key exception). processPayload returns normally ⇒ process()
        // reaches the XACK line ⇒ XACK fires.
        reset(orderMapper, streamOps);
        doReturn(streamOps).when(redis).opsForStream();
        doReturn(valueOps).when(redis).opsForValue();
        AtomicBoolean tick2Delivered = new AtomicBoolean(false);
        when(streamOps.read(any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(), any())).thenAnswer(inv -> tick2Delivered.compareAndSet(false, true)
                ? List.of(rec) : List.of());
        Order existing = new Order();
        existing.setId(42L);
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(existing);

        service.drain();

        // Exactly ONE acknowledge across both ticks — the crash tick did NOT ack (processPayload
        // threw), the redelivery tick DID ack (processPayload returned normally after ack-and-skip).
        verify(streamOps, times(1)).acknowledge(eq(STREAM), eq(GROUP), eq(recordId));
        // Tick-1's crash path (non-idempotency violation, no existing order) legitimately
        // recredits Redis stock (the order wasn't persisted) — exactly once. Tick-2's genuine
        // idempotency dup (existing order found) does NOT recredit (ack-and-skip). So recredit
        // is called exactly ONCE total — proving no double-decrement on the deduped redelivery.
        verify(stockGateService, times(1)).recredit(anyLong(), anyInt());
    }

    /**
     * Step 1 invariant 2: XACK runs only after the durable write succeeds (success path).
     * When processPayload completes normally, process() reaches the XACK line and acks the record.
     */
    @Test
    void successPath_xackRunsAfterCommit() {
        String payload = buildPayload();
        RecordId recordId = RecordId.of("2-0");
        MapRecord<String, String, String> rec =
                Record.<String, String, String>of(Map.of("payload", payload))
                        .withId(recordId).withStreamKey(STREAM);
        doReturn(streamOps).when(redis).opsForStream();
        doReturn(valueOps).when(redis).opsForValue();
        AtomicBoolean delivered = new AtomicBoolean(false);
        when(streamOps.read(any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(), any())).thenAnswer(inv -> delivered.compareAndSet(false, true)
                ? List.of(rec) : List.of());
        when(orderMapper.insert(any(Order.class))).thenReturn(1);
        when(productMapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
        when(productMapper.selectById(anyLong())).thenReturn(productFixture());

        service.drain();

        // processPayload returned normally (order inserted + stock decremented) → process()
        // reached the XACK line → exactly one acknowledge for this record.
        verify(streamOps, times(1)).acknowledge(eq(STREAM), eq(GROUP), eq(recordId));
    }

    /**
     * Step 1 invariant 3: poison-message escape. A payload that fails to JSON-parse is XACKed
     * terminal immediately (not left in the PEL to livelock) — preserving the existing
     * poison-escape behavior under the new Stream path.
     */
    @Test
    void poisonPayload_xackedTerminal_notLivelockedInPel() {
        RecordId recordId = RecordId.of("3-0");
        MapRecord<String, String, String> rec =
                Record.<String, String, String>of(Map.of("payload", "{not json"))
                        .withId(recordId).withStreamKey(STREAM);
        doReturn(streamOps).when(redis).opsForStream();
        AtomicBoolean delivered = new AtomicBoolean(false);
        when(streamOps.read(any(org.springframework.data.redis.connection.stream.Consumer.class),
                any(), any())).thenAnswer(inv -> delivered.compareAndSet(false, true)
                ? List.of(rec) : List.of());

        service.drain();

        // Poison payloads are acked terminal (escape the PEL) — no livelock.
        verify(streamOps, times(1)).acknowledge(eq(STREAM), eq(GROUP), eq(recordId));
        // And the DB write path was never entered.
        verify(orderMapper, never()).insert(any(Order.class));
    }

    /**
     * afterCommit hook branch: when a Spring tx IS active (production path),
     * process() registers a TransactionSynchronization whose afterCommit() does the XACK.
     * This test proves XACK is deferred to afterCommit — it does NOT fire inside the method body
     * (before commit). A regression that moves XACK back into the method body (before the
     * afterCommit hook fires) would fail this test because XACK would happen before afterCommit
     * is invoked.
     *
     * <p>Flow: initSynchronization() → process(rec) with processPayload stubbed to return
     * normally → assert a synchronization was registered → assert XACK NOT yet called (the
     * proxy hasn't committed) → simulate commit by invoking afterCommit() → assert XACK THEN
     * happens.
     */
    @Test
    void afterCommitHook_xackDeferredUntilAfterCommit_whenTxActive() {
        // Activate tx synchronization so isSynchronizationActive() returns true (production path).
        TransactionSynchronizationManager.initSynchronization();
        try {
            String payload = buildPayload();
            RecordId recordId = RecordId.of("10-0");
            MapRecord<String, String, String> rec =
                    Record.<String, String, String>of(Map.of("payload", payload))
                            .withId(recordId).withStreamKey(STREAM);
            doReturn(streamOps).when(redis).opsForStream();
            doReturn(valueOps).when(redis).opsForValue();
            // processPayload stubbed to return normally (all DB writes succeed → proxy will commit).
            when(orderMapper.insert(any(Order.class))).thenReturn(1);
            when(productMapper.decrementStock(anyLong(), anyInt())).thenReturn(1);
            when(productMapper.selectById(anyLong())).thenReturn(productFixture());

            // Call process() directly — the @Transactional is inert (no Spring proxy), but the
            // isSynchronizationActive() guard IS true, so the afterCommit hook branch runs.
            service.process(rec);

            // ASSERT 1: a TransactionSynchronization WAS registered (the afterCommit hook).
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            // ASSERT 2: XACK did NOT happen yet — the proxy hasn't committed, so afterCommit
            // hasn't fired. This is the critical assertion: XACK is deferred to afterCommit,
            // NOT executed inside the method body before commit.
            verify(streamOps, never()).acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class));

            // Simulate the proxy's commit: invoke afterCommit() on the registered synchronization.
            syncs.get(0).afterCommit();

            // ASSERT 3: XACK NOW happens — afterCommit fired (the tx committed successfully).
            verify(streamOps, times(1)).acknowledge(eq(STREAM), eq(GROUP), eq(recordId));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * afterCommit hook branch — commit FAILURE: when processPayload throws (the durable write
     * fails before commit), the afterCommit hook never fires (the tx rolls back) → NO XACK →
     * the message stays in the PEL for redelivery. This is the exact lost-message window the
     * afterCommit fix targets: if XACK were in the method body (before the throw), the message
     * would be ACKed but the DB tx rolled back → lost with no redelivery.
     *
     * <p>Flow: initSynchronization() → process(rec) with processPayload stubbed to throw →
     * the exception propagates past the fallback XACK guard (never reached) → assert NO
     * synchronization's afterCommit was invoked AND XACK NEVER happens.
     */
    @Test
    void afterCommitHook_commitFailure_noXack_messageStaysInPel() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            String payload = buildPayload();
            RecordId recordId = RecordId.of("11-0");
            MapRecord<String, String, String> rec =
                    Record.<String, String, String>of(Map.of("payload", payload))
                            .withId(recordId).withStreamKey(STREAM);
            // processPayload throws mid-flight (simulating a crash/DB error before commit).
            when(orderMapper.insert(any(Order.class)))
                    .thenThrow(new DataIntegrityViolationException("non-idempotency constraint"));
            when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(null);

            // process() throws because processPayload throws — the exception propagates out.
            assertThatThrownBy(() -> service.process(rec))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // The afterCommit hook WAS registered (before processPayload ran), but in a real tx
            // the rollback would prevent it from firing. We do NOT invoke afterCommit() here
            // (simulating rollback). Assert XACK NEVER happens — the message stays in the PEL.
            verify(streamOps, never()).acknowledge(eq(STREAM), eq(GROUP), any(RecordId.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Product productFixture() {
        Product p = new Product();
        p.setId(1L);
        p.setName("Widget");
        p.setPriceInCents(1999);
        p.setStock(10);
        return p;
    }

    private String buildPayload() {
        try {
            var node = objectMapper.createObjectNode();
            node.put("orderId", 123L);
            node.put("userId", 7L);
            node.put("customerName", "Bob");
            node.put("customerEmail", "b@x.com");
            node.put("shippingAddress", "here");
            node.put("totalInCents", 3998);
            node.put("idempotencyKey", "idem-1");
            var items = node.putArray("items");
            var i = items.addObject();
            i.put("productId", 1);
            i.put("quantity", 2);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
