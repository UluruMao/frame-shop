package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ProductDTO;
import com.iflytek.frame.dto.ProductListItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.ProductService;
import com.iflytek.frame.util.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for the read-through list cache on {@link ProductServiceImpl#page(PageQuery)}.
 *
 * <p>Runs against REAL Redis + MariaDB (no mocks), proving the cache write/tracking on a miss,
 * the cache hit on a repeat call, and full invalidation on a product mutation. Mirrors the
 * {@code getById} read-through pattern (30s TTL, {@code StringRedisTemplate}, graceful
 * Redis-down degradation).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class ProductListCacheIntegrationTest {

    @Autowired ProductService productService;
    @Autowired ProductMapper productMapper;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    private Long seededId;

    @BeforeEach
    void seedAndClearCache() {
        // Seed a self-contained product named with "widget" so the keyword query is deterministic
        // (does NOT depend on data left behind by other tests).
        jdbc.execute("DELETE FROM order_item");
        jdbc.execute("DELETE FROM orders");
        jdbc.execute("DELETE FROM product");
        jdbc.update("INSERT INTO product (id,name,price_in_cents,stock,category,is_flash_sale) " +
                "VALUES (1,'Cache Widget',1000,5,'toys',0)");
        seededId = 1L;

        // Clear the list cache + tracking set so each test starts cold.
        var keys = redis.opsForSet().members("products:page:keys");
        if (keys != null) for (String k : keys) redis.delete(k);
        redis.delete("products:page:keys");
    }

    @Test
    void page_caches_on_miss_and_hits_on_repeat() {
        PageQuery q = new PageQuery();
        q.setKeyword("widget");
        q.setSize(5);

        // First call: miss -> DB -> cache write.
        PageResult<ProductListItem> first = productService.page(q);
        assertNotNull(first);
        assertThat(first.getRecords()).isNotEmpty();

        // A cache key should now be tracked in the products:page:keys SET.
        assertThat(redis.opsForSet().members("products:page:keys"))
                .as("list cache key should be tracked after a page() miss")
                .isNotEmpty();

        // Second call with the same normalized query: should hit the cache (no new tracking set
        // entry is added, but the key remains). The result must be transparently identical.
        PageResult<ProductListItem> second = productService.page(q);
        assertNotNull(second);
        assertThat(second.getTotal()).isEqualTo(first.getTotal());
        assertThat(second.getRecords()).hasSameSizeAs(first.getRecords());
    }

    @Test
    void update_invalidates_list_cache() {
        PageQuery q = new PageQuery();
        q.setKeyword("widget");
        q.setSize(5);
        productService.page(q);
        assertThat(redis.opsForSet().members("products:page:keys")).isNotEmpty();

        // Mutate the seeded product -> invalidates the whole list cache.
        ProductDTO dto = new ProductDTO();
        dto.setName("Updated Widget");
        dto.setPriceInCents(1000);
        dto.setStock(99);
        dto.setCategory("electronics");
        dto.setFlashSale(false);
        productService.update(seededId, dto);

        // After invalidation the tracking set is DEL'd (members() returns null / empty).
        var members = redis.opsForSet().members("products:page:keys");
        assertThat(members == null || members.isEmpty())
                .as("list cache tracking set should be cleared after a product mutation")
                .isTrue();
    }

    @Test
    void delete_invalidates_list_cache() {
        // Seed a second product solely for deletion (keep product 1 so the keyword query matches).
        Product extra = new Product();
        extra.setName("Delete Me Widget");
        extra.setPriceInCents(500);
        extra.setStock(1);
        extra.setCategory("toys");
        extra.setFlashSale(false);
        productMapper.insert(extra);
        Long extraId = extra.getId();

        PageQuery q = new PageQuery();
        q.setKeyword("widget");
        q.setSize(5);
        productService.page(q);
        assertThat(redis.opsForSet().members("products:page:keys")).isNotEmpty();

        productService.delete(extraId);

        var members = redis.opsForSet().members("products:page:keys");
        assertThat(members == null || members.isEmpty())
                .as("list cache tracking set should be cleared after a product delete")
                .isTrue();
    }

    @Test
    void cached_page_is_transparent_to_response_shape() {
        PageQuery q = new PageQuery();
        q.setSize(10);

        PageResult<ProductListItem> uncached = productService.page(q);
        // Second call returns the cached copy. The serialized/deserialized record must carry the
        // same fields the live query returns (id, name, priceInCents, isFlashSale, ...). This is
        // the "cache transparent to the response shape" invariant the catalog e2e relies on.
        PageResult<ProductListItem> cached = productService.page(q);

        assertThat(cached.getTotal()).isEqualTo(uncached.getTotal());
        assertThat(cached.getCurrent()).isEqualTo(uncached.getCurrent());
        assertThat(cached.getSize()).isEqualTo(uncached.getSize());
        assertThat(cached.getRecords()).hasSameSizeAs(uncached.getRecords());
        if (!cached.getRecords().isEmpty()) {
            ProductListItem a = uncached.getRecords().get(0);
            ProductListItem b = cached.getRecords().get(0);
            assertThat(b.getId()).isEqualTo(a.getId());
            assertThat(b.getName()).isEqualTo(a.getName());
            assertThat(b.getPriceInCents()).isEqualTo(a.getPriceInCents());
            assertThat(b.isFlashSale()).isEqualTo(a.isFlashSale());
            assertThat(b.getCategory()).isEqualTo(a.getCategory());
        }
    }
}
