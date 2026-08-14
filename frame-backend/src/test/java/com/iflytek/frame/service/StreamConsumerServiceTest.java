package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.entity.Order;
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
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for the narrowed idempotency-catch disambiguation in
 * {@link StreamConsumerService#process}.
 *
 * <p>These tests manually init/clear {@link TransactionSynchronizationManager} so the
 * afterCommit-XACK hook can be registered (the guard at the top of {@code process()}
 * requires an active synchronization). They verify the two branches of the
 * {@link DataIntegrityViolationException} catch:
 * <ul>
 *   <li>Genuine idempotency dup ({@code selectByIdempotencyKey} returns a row) → ack-and-skip.</li>
 *   <li>Non-idempotency violation ({@code selectByIdempotencyKey} returns null) → re-credit + rethrow.</li>
 * </ul>
 *
 * <p>The full consumer flow (Lua gate + XADD + drain + DB-backstop decrement + FAILED branch)
 * is exercised end-to-end by the Task 7 integration test against real Redis + DB.
 */
@ExtendWith(MockitoExtension.class)
class StreamConsumerServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock ProductMapper productMapper;
    @Mock StockGateService stockGateService;
    @Mock StreamOperations<String, Object, Object> streamOps;
    @Mock ValueOperations<String, String> valueOps;

    private StreamConsumerService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new StreamConsumerService(redis, orderMapper, orderItemMapper, productMapper, stockGateService);
        // Manually init transaction synchronization so process() can register the afterCommit hook.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> record(String payload) {
        MapRecord<String, Object, Object> rec = (MapRecord<String, Object, Object>)
                (MapRecord<String, ?, ?>) MapRecord.create("orders:pending",
                        Map.of("payload", (Object) payload, "orderId", (Object) "123"));
        return rec.withId(RecordId.of("1-0"));
    }

    @Test
    void process_genuineIdempotencyDup_acksAndSkips() {
        String payload = buildPayload();
        MapRecord<String, Object, Object> rec = record(payload);

        // insert throws DIV (the idempotency_key UNIQUE constraint fired)
        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
        // selectByIdempotencyKey returns a row → genuine dup → ack-and-skip
        Order existing = new Order();
        existing.setId(42L);
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(existing);
        when(redis.opsForValue()).thenReturn(valueOps);

        service.process(rec);

        // must NOT re-credit Redis stock (prior delivery already decremented)
        verify(stockGateService, never()).recredit(anyLong(), anyInt());
        // must NOT decrement DB stock
        verify(productMapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void process_nonIdempotencyViolation_recreditsAndRethrows() {
        String payload = buildPayload();
        MapRecord<String, Object, Object> rec = record(payload);

        // insert throws DIV, but NOT an idempotency-key dup
        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));
        // selectByIdempotencyKey returns null → NOT an idempotency dup → re-credit + rethrow
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(null);
        when(redis.opsForValue()).thenReturn(valueOps);
        // SET NX guard returns true → first delivery → recredit runs
        when(valueOps.setIfAbsent(eq("recredited:1-0"), eq("1"), eq(3600L), any(TimeUnit.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.process(rec))
                .isInstanceOf(DataIntegrityViolationException.class);

        // must re-credit Redis stock for every item in the payload
        verify(stockGateService).recredit(1L, 2);
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
