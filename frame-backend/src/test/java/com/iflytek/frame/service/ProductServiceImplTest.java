package com.iflytek.frame.service;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.ProductServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
    @Mock ProductMapper productMapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks ProductServiceImpl productService;

    @BeforeEach
    void wireRealObjectMapper() {
        // The service now injects the Spring-managed ObjectMapper (Jackson 3 / tools.jackson,
        // auto-configured by Spring Boot 4.1 with java.time support built in — no separate
        // JavaTimeModule needed in Jackson 3). In this Mockito-only unit test there's no Spring
        // context, so wire a real Jackson 3 ObjectMapper mirroring the prod one. Against the
        // OLD bug (`new com.fasterxml.jackson.databind.ObjectMapper()`, Jackson 2.x with no
        // time support), the cacheHit_withLocalDateTimeFields_deserializes test fails with
        // InvalidDefinitionException — exactly the production HTTP 500.
        ObjectMapper prodLike = new ObjectMapper();
        org.springframework.test.util.ReflectionTestUtils.setField(productService, "objectMapper", prodLike);
    }

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
    void getById_cacheHit_withLocalDateTimeFields_deserializes() {
        // Regression for the production bug: GET /products/{id} returned HTTP 500 for EVERY
        // product because getById used `new ObjectMapper()` (no JavaTimeModule) → could not
        // (de)serialize the LocalDateTime fields every Product carries (via BaseEntity) →
        // InvalidDefinitionException, caught by GlobalExceptionHandler → 500. This cascaded to
        // PUT/DELETE, which call getById first. The fix injects the Spring-managed ObjectMapper
        // (auto-configured with JavaTimeModule). This test would FAIL against `new ObjectMapper()`.
        //
        // A real ObjectMapper (not a mock) is wired into the service (via @BeforeEach) so the
        // test proves actual deserialization works — mirroring how Spring Boot configures the
        // prod ObjectMapper.

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("product:1")).thenReturn(
                "{\"id\":1,\"name\":\"Cached\",\"priceInCents\":1999,\"stock\":5,"
                + "\"flashSale\":true,\"flashSaleAt\":\"2026-08-18T06:41:27\","
                + "\"createTime\":\"2026-08-18T06:41:27\",\"updateTime\":\"2026-08-18T06:41:27\"}");

        Product result = productService.getById(1L);

        assertThat(result.getName()).isEqualTo("Cached");
        assertThat(result.getId()).isEqualTo(1L);
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
