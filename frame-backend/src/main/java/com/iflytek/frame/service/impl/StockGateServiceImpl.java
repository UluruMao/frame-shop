package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.StockGateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class StockGateServiceImpl implements StockGateService {

    private static final Logger log = LoggerFactory.getLogger(StockGateServiceImpl.class);

    private final StringRedisTemplate redis;
    private final ProductMapper productMapper;

    private RedisScript<String> stockGateScript;

    @PostConstruct
    void init() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/stock_gate.lua")));
        script.setResultType(String.class);
        this.stockGateScript = script;
    }

    @Override
    public String tryClaimStock(List<Long> productIds, List<Integer> quantities) {
        List<String> keys = productIds.stream().map(id -> "stock:" + id).collect(Collectors.toList());
        String[] args = quantities.stream().map(String::valueOf).toArray(String[]::new);
        // Pass the String[] WITHOUT a cast so the compiler unwraps it into the
        // Object... varargs — each element becomes an ARGV[i] the Lua script reads
        // via tonumber(ARGV[i]). Casting to (Object) would pass the whole array as a
        // single vararg element, breaking real Redis execution.
        String result = redis.execute(stockGateScript, keys, args);
        // result is "OK" | "FAIL" | "NOT_INITIALIZED" (see stock_gate.lua). The caller
        // (CheckoutServiceImpl) maps each to an HTTP status. Null defensively → FAIL
        // (fail-closed: if Redis returned nothing, don't claim stock).
        return result != null ? result : "FAIL";
    }

    @Override
    public void loadStock(Long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "product not found: " + productId);
        }
        redis.opsForValue().set("stock:" + productId, String.valueOf(p.getStock()));
    }

    @Override
    public void recoverStock(Long productId) {
        loadStock(productId); // re-load from current DB stock
    }

    @Override
    public void recredit(Long productId, int qty) {
        redis.opsForValue().increment("stock:" + productId, qty);
    }

    /**
     * Functional time-gated flash sales: every 10s, scan for products flagged
     * {@code is_flash_sale = true} whose {@code flash_sale_at <= NOW()} and whose
     * {@code stock:{id}} key is NOT yet in Redis (sale not yet opened). For each,
     * call {@link #loadStock(Long)} to auto-open the sale at the configured time.
     *
     * <p>This means a flash sale actually opens at {@code flashSaleAt} without an
     * admin manually clicking "open sale." The admin's manual
     * {@code POST /admin/sales/{productId}} endpoint stays for immediate testing.
     *
     * <p>Guarded by {@code hasKey} so a sale already in progress (key exists) is
     * never re-loaded mid-flight (which would reset Redis stock to DB stock,
     * losing decrements). {@code @Scheduled} is enabled by
     * {@code @EnableScheduling} on {@link com.iflytek.frame.FrameBackendApplication}.
     */
    @Scheduled(fixedRate = 10_000)
    public void openDueFlashSales() {
        // NOTE: plain QueryWrapper (not LambdaQueryWrapper) by design. The entity field is
        // `boolean isFlashSale`; a LambdaQueryWrapper would reference Product::isFlashSale,
        // but MyBatis-Plus's lambda resolver strips the `is` prefix from a boolean getter,
        // looks up property `flashSale` in the lambda cache, and MISSES (the field/column is
        // isFlashSale/is_flash_sale) → MybatisPlusException, swallowed by the catch below,
        // silently no-opping the scheduler (flash sales never auto-open). Column-string
        // form has no property-name resolution step, so it can't hit that ambiguity.
        // Regression test: FlashSaleSchedulerIntegrationTest (real MariaDB, not mocked mapper).
        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        wrapper.eq("is_flash_sale", true).le("flash_sale_at", LocalDateTime.now());
        List<Product> due;
        try {
            due = productMapper.selectList(wrapper);
        } catch (Exception e) {
            log.warn("Flash-sale scheduler DB scan failed: {}", e.getMessage());
            return;
        }
        for (Product p : due) {
            String key = "stock:" + p.getId();
            try {
                if (Boolean.FALSE.equals(redis.hasKey(key))) {
                    log.info("Auto-opening flash sale for product {} (flashSaleAt={})", p.getId(), p.getFlashSaleAt());
                    loadStock(p.getId());
                }
            } catch (Exception e) {
                log.warn("Flash-sale scheduler failed to open product {}: {}", p.getId(), e.getMessage());
            }
        }
    }
}
