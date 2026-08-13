package com.iflytek.frame.service;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
    @Mock ProductMapper productMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks ProductServiceImpl productService;

    @Test
    void getById_cacheMiss_loadsFromDb_andCachesWithTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenReturn(null);
        Product p = new Product(); p.setId(1L); p.setName("Widget");
        when(productMapper.selectById(1L)).thenReturn(p);

        Product result = productService.getById(1L);

        assertThat(result.getName()).isEqualTo("Widget");
        verify(valueOps).set(eq("product:1"), anyString(), eq(30L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void getById_cacheHit_doesNotHitDb() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenReturn("{\"id\":1,\"name\":\"Cached\"}");
        productService.getById(1L);
        verify(productMapper, never()).selectById(any());
    }

    @Test
    void getById_redisDown_fallsThroughToDb_andReturnsProduct() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenThrow(new RedisConnectionFailureException("connection refused"));
        Product p = new Product(); p.setId(1L); p.setName("Widget");
        when(productMapper.selectById(1L)).thenReturn(p);

        Product result = productService.getById(1L);

        assertThat(result.getName()).isEqualTo("Widget");
        verify(productMapper).selectById(1L);
    }
}
