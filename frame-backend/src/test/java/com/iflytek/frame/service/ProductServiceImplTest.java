package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.ProductServiceImpl;
import com.iflytek.frame.util.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    @Test
    void page_normalizes_sortField_whitelist_fallsBackToCreateTime() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        com.iflytek.frame.dto.ProductListItem item = new com.iflytek.frame.dto.ProductListItem();
        item.setName("X");
        mp.setTotal(1); mp.setRecords(java.util.List.of(item));
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setSortField("bogus");   // not in whitelist
        q.setSortOrder("desc");
        PageResult<ProductListItem> r = productService.page(q);

        assertThat(r.getRecords()).hasSize(1);
        // sortField stays null-ish so <choose> defaults to create_time
        verify(productMapper).pageProducts(any(), argThat(qq -> qq.getSortField() == null));
    }

    @Test
    void page_normalizes_sortOrder_and_sets_sortOrderSql() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setSortField("price");
        q.setSortOrder("ASC");     // uppercase
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq -> " ASC".equals(qq.getSortOrderSql())));

        // garbage sortOrder → default DESC
        PageQuery q2 = new PageQuery();
        q2.setSortField("price");
        q2.setSortOrder("sideways");
        productService.page(q2);
        verify(productMapper).pageProducts(any(), argThat(qq -> " DESC".equals(qq.getSortOrderSql())));
    }

    @Test
    void page_splits_keyword_on_comma_and_space_drops_empties() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setKeyword("red,, widget  ,");
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq ->
                qq.getKeywords() != null && qq.getKeywords().size() == 2
                        && qq.getKeywords().contains("red") && qq.getKeywords().contains("widget")));
    }

    @Test
    void page_blank_keyword_yields_null_keywords() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProductListItem> mp =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>();
        mp.setTotal(0); mp.setRecords(java.util.List.of());
        when(productMapper.pageProducts(any(), any())).thenReturn(mp);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PageQuery q = new PageQuery();
        q.setKeyword("   ");
        productService.page(q);
        verify(productMapper).pageProducts(any(), argThat(qq -> qq.getKeywords() == null));
    }

    @Test
    void getDetail_notFound_throws() {
        when(productMapper.findDetailById(999999L)).thenReturn(null);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> productService.getDetail(999999L))
                .isInstanceOf(com.iflytek.frame.exception.BusinessException.class);
    }
}
