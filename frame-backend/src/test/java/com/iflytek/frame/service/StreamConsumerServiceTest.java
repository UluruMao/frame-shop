package com.iflytek.frame.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StreamConsumerService#processPayload} — the idempotency-catch
 * disambiguation branches.
 */
@ExtendWith(MockitoExtension.class)
class StreamConsumerServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock OrderMapper orderMapper;
    @Mock OrderItemMapper orderItemMapper;
    @Mock ProductMapper productMapper;
    @Mock StockGateService stockGateService;
    @Mock ValueOperations<String, String> valueOps;

    private StreamConsumerService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new StreamConsumerService(redis, orderMapper, orderItemMapper, productMapper, stockGateService);
        TransactionSynchronizationManager.initSynchronization();
    }

    @BeforeEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void process_genuineIdempotencyDup_skips() {
        String payload = buildPayload();

        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("uk_idempotency_key"));
        Order existing = new Order();
        existing.setId(42L);
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(existing);
        when(redis.opsForValue()).thenReturn(valueOps);

        service.processPayload(payload);

        verify(stockGateService, never()).recredit(anyLong(), anyInt());
        verify(productMapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void process_nonIdempotencyViolation_recreditsAndRethrows() {
        String payload = buildPayload();

        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));
        when(orderMapper.selectByIdempotencyKey("idem-1")).thenReturn(null);

        assertThatThrownBy(() -> service.processPayload(payload))
                .isInstanceOf(DataIntegrityViolationException.class);

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
