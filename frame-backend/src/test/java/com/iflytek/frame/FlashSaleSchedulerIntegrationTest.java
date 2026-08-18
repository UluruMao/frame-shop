package com.iflytek.frame;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.impl.StockGateServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the time-gated flash-sale auto-open scheduler
 * ({@link com.iflytek.frame.service.impl.StockGateServiceImpl#openDueFlashSales()}).
 *
 * <p>The scheduler builds a MyBatis-Plus query filtering {@code is_flash_sale = true AND
 * flash_sale_at <= now()}. An earlier version used a {@code LambdaQueryWrapper} with
 * {@code Product::isFlashSale} — but for a {@code boolean isFlashSale} field, MyBatis-Plus's
 * lambda resolver strips the {@code is} prefix and looks up property {@code flashSale} in the
 * lambda cache, which misses (the field/column is {@code isFlashSale}/{@code is_flash_sale}).
 * The resulting {@code MybatisPlusException} was swallowed by the scheduler's {@code try/catch}
 * as a WARN, so the scheduler silently NO-OPPED: time-gated flash sales never auto-opened.
 *
 * <p>The unit test {@code StockGateServiceImplTest} mocks {@code productMapper.selectList},
 * so it never exercises the real lambda-cache resolution and cannot catch this. This test
 * runs against REAL MariaDB + Redis (the {@code @SpringBootTest} pattern used by
 * {@code CheckoutFlowIntegrationTest}) so the scheduler's query must actually resolve.
 *
 * <p>Setup: insert a flash-sale product whose {@code flashSaleAt} is in the past but whose
 * {@code stock:{id}} Redis key is absent (sale not yet opened). Call {@code openDueFlashSales()}.
 * If the query resolves, the scheduler finds the product and calls {@code loadStock}, setting
 * {@code stock:{id}} in Redis. If the lambda is broken, the {@code try/catch} swallows the error
 * and the key stays absent → assertion fails.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
class FlashSaleSchedulerIntegrationTest {

    @Autowired StockGateServiceImpl stockGateService;
    @Autowired ProductMapper productMapper;
    @Autowired StringRedisTemplate redis;

    @Test
    void openDueFlashSales_opensSaleForPastDueProduct_queryResolves() {
        Product p = new Product();
        p.setName("Scheduler ITest Widget");
        p.setPriceInCents(199);
        p.setStock(4);
        p.setFlashSale(true);
        p.setFlashSaleAt(LocalDateTime.now().minusMinutes(5)); // due now
        productMapper.insert(p);

        // ensure the sale is NOT already open (no stock key) so the scheduler will act
        redis.delete("stock:" + p.getId());

        try {
            stockGateService.openDueFlashSales();

            // If the scheduler's query resolved, it found this due product and called loadStock,
            // seeding Redis stock:{id} with the DB stock. A broken query (swallowed exception)
            // leaves the key absent → this assertion fails.
            assertThat(redis.opsForValue().get("stock:" + p.getId()))
                    .as("scheduler should have auto-opened the due flash sale and seeded Redis stock")
                    .isEqualTo("4");
        } finally {
            redis.delete("stock:" + p.getId());
            productMapper.deleteById(p.getId());
        }
    }
}
