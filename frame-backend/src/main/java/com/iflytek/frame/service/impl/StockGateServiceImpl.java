package com.iflytek.frame.service.impl;

import com.iflytek.frame.entity.Product;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.StockGateService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class StockGateServiceImpl implements StockGateService {

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
    public boolean tryClaimStock(List<Long> productIds, List<Integer> quantities) {
        List<String> keys = productIds.stream().map(id -> "stock:" + id).collect(Collectors.toList());
        String[] args = quantities.stream().map(String::valueOf).toArray(String[]::new);
        // Pass the String[] WITHOUT a cast so the compiler unwraps it into the
        // Object... varargs — each element becomes an ARGV[i] the Lua script reads
        // via tonumber(ARGV[i]). Casting to (Object) would pass the whole array as a
        // single vararg element, breaking real Redis execution.
        String result = redis.execute(stockGateScript, keys, args);
        return "OK".equals(result);
    }

    @Override
    public void loadStock(Long productId) {
        Product p = productMapper.selectById(productId);
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
}
