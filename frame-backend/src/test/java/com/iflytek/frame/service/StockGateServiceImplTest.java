package com.iflytek.frame.service;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.StockGateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StockGateServiceImpl.
 *
 * <p>{@code tryClaimStock} (the two-pass Lua gate) is NOT unit-tested here: its correctness
 * is the Lua script ({@code stock_gate.lua}) + the Redis {@code execute} contract, which a
 * Mockito mock of {@code StringRedisTemplate.execute(RedisScript, List, Object...)} cannot
 * meaningfully verify (varargs stub-matching is brittle and would only assert that the impl
 * delegates, not that the Lua is correct). It is instead proven end-to-end by the Task 7
 * integration test against real Redis (load/mutex: N buyers, K succeed, stock ends at 0).
 *
 * <p>What IS worth unit-testing is {@code loadStock} (pure delegation to {@code opsForValue().set})
 * — that path is verifiable in isolation.
 */
@ExtendWith(MockitoExtension.class)
class StockGateServiceImplTest {

    @Mock StringRedisTemplate redis;
    @Mock ProductMapper productMapper;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks StockGateServiceImpl stockGateService;

    @Test
    void loadStock_setsRedisStockFromDb() {
        Product p = new Product();
        p.setId(1L);
        p.setStock(5);
        when(productMapper.selectById(1L)).thenReturn(p);
        when(redis.opsForValue()).thenReturn(valueOps);

        stockGateService.loadStock(1L);

        verify(valueOps).set("stock:1", "5");
    }

    @Test
    void recoverStock_reloadsFromCurrentDbStock() {
        Product p = new Product();
        p.setId(2L);
        p.setStock(0); // DB drifted to 0 after sales; recovery loads current value, not original
        when(productMapper.selectById(2L)).thenReturn(p);
        when(redis.opsForValue()).thenReturn(valueOps);

        stockGateService.recoverStock(2L);

        verify(valueOps).set("stock:2", "0");
    }

    @Test
    void recredit_incrementsRedisStock() {
        when(redis.opsForValue()).thenReturn(valueOps);

        stockGateService.recredit(3L, 4);

        verify(valueOps).increment("stock:3", 4);
    }
}
