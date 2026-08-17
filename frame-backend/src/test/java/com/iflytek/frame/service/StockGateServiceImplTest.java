package com.iflytek.frame.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void loadStock_productNotFound_throwsNotFound_notNpe() {
        when(productMapper.selectById(99L)).thenReturn(null); // product doesn't exist

        assertThatThrownBy(() -> stockGateService.loadStock(99L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class)
                .hasMessageContaining("product not found: 99");
        // must NOT touch Redis — no stock to load
        verify(redis, never()).opsForValue();
    }

    @Test
    void openDueFlashSales_opensSaleWhenStockKeyAbsent() {
        // A flash-sale product whose flashSaleAt is in the past → due to open.
        Product due = new Product();
        due.setId(10L);
        due.setFlashSale(true);
        due.setFlashSaleAt(LocalDateTime.now().minusMinutes(5));
        due.setStock(7);
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(due));
        // stock:10 not in Redis → sale not yet opened
        when(redis.hasKey("stock:10")).thenReturn(false);
        when(productMapper.selectById(10L)).thenReturn(due);
        when(redis.opsForValue()).thenReturn(valueOps);

        stockGateService.openDueFlashSales();

        // loadStock(10) was called → Redis stock:10 set to DB stock
        verify(valueOps).set("stock:10", "7");
    }

    @Test
    void openDueFlashSales_skipsSaleAlreadyOpen() {
        Product due = new Product();
        due.setId(11L);
        due.setFlashSale(true);
        due.setFlashSaleAt(LocalDateTime.now().minusMinutes(1));
        due.setStock(3);
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of(due));
        // stock:11 already in Redis → sale already open; must NOT re-load (would reset stock)
        when(redis.hasKey("stock:11")).thenReturn(true);

        stockGateService.openDueFlashSales();

        // Must NOT call loadStock — key exists means sale is live
        verify(productMapper, never()).selectById(11L);
        verify(redis, never()).opsForValue();
    }

    @Test
    void openDueFlashSales_noDueProducts_doesNothing() {
        when(productMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        stockGateService.openDueFlashSales();

        verify(redis, never()).hasKey(any());
        verify(redis, never()).opsForValue();
    }
}
