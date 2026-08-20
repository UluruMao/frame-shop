package com.iflytek.frame.service;

import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrderStockReleaseService} — the shared compensating stock release.
 *
 * <p>Verifies the Redis-down-does-not-block contract: when {@code stockGateService.recredit}
 * or {@code redis.delete} throws {@link RedisConnectionFailureException}, the DB recredit
 * ({@code productMapper.recreditStock}) still runs for every item and {@code release} does
 * not throw.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderStockReleaseServiceTest {

    @Mock OrderItemMapper orderItemMapper;
    @Mock StockGateService stockGateService;
    @Mock ProductMapper productMapper;
    @Mock StringRedisTemplate redis;
    @InjectMocks OrderStockReleaseService service;

    private OrderItem item(long pid, int qty) {
        OrderItem it = new OrderItem();
        it.setOrderId(10L);
        it.setProductId(pid);
        it.setQuantity(qty);
        return it;
    }

    @Test
    void release_recreditsRedisAndDbAndDelsSeckill_perItem() {
        Order o = new Order();
        o.setId(10L);
        o.setUserId(7L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 2), item(2L, 3)));

        service.release(o);

        // Redis recredit per item
        verify(stockGateService).recredit(1L, 2);
        verify(stockGateService).recredit(2L, 3);
        // DB recredit per item
        verify(productMapper).recreditStock(1L, 2);
        verify(productMapper).recreditStock(2L, 3);
        // seckill lock delete per item
        verify(redis).delete("seckill:1:7");
        verify(redis).delete("seckill:2:7");
    }

    @Test
    void release_redisRecreditDown_dbRecreditStillRunsAndDoesNotThrow() {
        Order o = new Order();
        o.setId(10L);
        o.setUserId(7L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 2)));
        // Redis recredit throws (Redis down)
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(stockGateService).recredit(1L, 2);

        // Must NOT throw — DB recredit continues
        service.release(o);

        // DB recredit still ran
        verify(productMapper).recreditStock(1L, 2);
        // seckill delete still attempted (its own try/catch)
        verify(redis).delete("seckill:1:7");
    }

    @Test
    void release_redisDeleteDown_doesNotThrow() {
        Order o = new Order();
        o.setId(10L);
        o.setUserId(7L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item(1L, 2)));
        // Redis delete throws (Redis down)
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redis).delete("seckill:1:7");

        // Must NOT throw
        service.release(o);

        verify(stockGateService).recredit(1L, 2);
        verify(productMapper).recreditStock(1L, 2);
    }

    @Test
    void release_noItems_noInteractions() {
        Order o = new Order();
        o.setId(10L);
        o.setUserId(7L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());

        service.release(o);

        verify(stockGateService, never()).recredit(anyLong(), anyInt());
        verify(productMapper, never()).recreditStock(anyLong(), anyInt());
        verify(redis, never()).delete(any(String.class));
    }
}
