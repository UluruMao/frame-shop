package com.iflytek.frame.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.Money;
import com.iflytek.frame.common.RedisKeys;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.CoinDiscountPreview;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.service.CoinService;
import com.iflytek.frame.service.StockGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Async hot-path checkout (Taobao seckill model).
 *
 * <p>The request never touches the DB for writes — it:
 * <ol>
 *   <li>Aggregates item quantities per product.</li>
 *   <li>Fetches products server-side for price authority ({@code selectBatchIds}).</li>
 *   <li>Redis Lua gate ({@code stockGateService.tryClaimStock}) — atomic all-or-nothing
 *       decrement across all items; FAIL → 409 "sold out".</li>
 *   <li>Computes the total from DB prices.</li>
 *   <li>{@code XADD}s an order message to {@code orders:pending} for the consumer to
 *       durably persist, sets {@code order_status:{id} = "queued"}, returns 202.</li>
 * </ol>
 *
 * <p>The durable write (Order + OrderItem inserts, idempotency, DB-backstop decrement)
 * moves to {@link StreamConsumerService#process} — the at-least-once consumer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {

    private final StringRedisTemplate redis;
    private final StockGateService stockGateService;
    private final ProductMapper productMapper;
    private final CoinService coinService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Accepted payment methods — validated before enqueuing so a bad value fails fast (PARAM_INVALID). */
    private static final Set<String> VALID_PAYMENT_METHODS =
            new HashSet<>(Arrays.asList("WECHAT", "ALIPAY", "BANK_CARD"));

    /**
     * Unique-per-JVM correlation-id generator. Seeded from millis + a per-JVM random
     * offset; each call increments. This MUST be unique per checkout call: the orderId is
     * the XADD stream-entry field value AND the key suffix for
     * {@code order_status:{orderId}} / {@code order_id_map:{orderId}}. If two checkouts
     * share an orderId, (a) the Redis status/map keys collide (status aliasing), and
     * (b) Spring Data Redis's stream deserialization confuses entries that share a field
     * value, causing the consumer to read the wrong payload for a record (order loss).
     * Unique per call eliminates both. The Order row's DB PK is separate (AUTO_INCREMENT)
     * — this is only the correlation id the client polls.
     *
     * <p>The seed mixes {@code System.currentTimeMillis()*1000} with a
     * {@link java.security.SecureRandom} offset so that a JVM restart within ~16min (which
     * would otherwise produce a millis-based seed close to the prior run's counter range,
     * and resets the counter) cannot overlap prior-run IDs — the random offset differs
     * per JVM, pushing the seed out of the prior run's sequence window.
     */
    private static final java.security.SecureRandom ORDER_SEQ_RNG = new java.security.SecureRandom();
    private static final AtomicLong ORDER_SEQ =
            new AtomicLong(System.currentTimeMillis() * 1000 + ORDER_SEQ_RNG.nextInt(1_000_000));

    @Override
    public CheckoutResponse checkout(CheckoutRequest request, Long userId) {
        // 0. Validate payment method up front — fail fast (PARAM_INVALID) before touching Redis/DB.
        if (request.getPaymentMethod() == null
                || !VALID_PAYMENT_METHODS.contains(request.getPaymentMethod().toUpperCase())) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "paymentMethod must be one of WECHAT/ALIPAY/BANK_CARD");
        }

        // 1. Aggregate quantities per product (dedup within the cart).
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (OrderItemDTO item : request.getItems()) {
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        // 2. Fetch products (server-side price authority — never trust the client).
        List<Product> products = productMapper.selectBatchIds(qtyByProduct.keySet());
        if (products.size() != qtyByProduct.size()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "one or more products not found");
        }

        // 2c. UC005/UC012: validate the coin-discount request BEFORE the stock gate (fail fast).
        int validatedCoinAmount = 0;
        boolean useCoins = Boolean.TRUE.equals(request.getUseCoins());
        if (useCoins) {
            CoinDiscountPreview preview =
                    coinService.previewCheckoutDiscount(userId, products, qtyByProduct);
            if (!Boolean.TRUE.equals(preview.getEligible())) {
                throw new BusinessException(ResultCode.PARAM_INVALID, preview.getReason());
            }
            int requested = request.getCoinAmount() == null ? 0 : request.getCoinAmount();
            if (requested <= 0) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "coin amount must be positive");
            }
            if (requested > preview.getUsable()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "coin amount exceeds allowed discount");
            }
            validatedCoinAmount = requested;
        }

        // 2b. One-per-user check (spec §6 step 3): reject users who already purchased.
        for (Long pid : qtyByProduct.keySet()) {
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.seckill(pid, userId)))) {
                throw new BusinessException(ResultCode.CONFLICT, "already purchased this item");
            }
        }

        // 3. Redis Lua gate (atomic, all-or-nothing — no partial decrements on FAIL).
        //    Gap 3: catch Redis outage → 503 (fail-closed, never bypass the gate).
        //    Gap 3b: the Lua script returns a tri-state String — "OK" proceed, "FAIL"
        //    sold-out 409, "NOT_INITIALIZED" stock-not-loaded 503 (distinct from sold out).
        List<Long> ids = new ArrayList<>(qtyByProduct.keySet());
        List<Integer> qtys = ids.stream().map(qtyByProduct::get).collect(Collectors.toList());
        String claimResult;
        try {
            claimResult = stockGateService.tryClaimStock(ids, qtys);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "stock gate unavailable");
        }
        switch (claimResult) {
            case "OK" -> { /* proceed — stock atomically decremented in Redis */ }
            case "NOT_INITIALIZED" -> throw new BusinessException(
                    ResultCode.SERVICE_UNAVAILABLE, "stock not initialized");
            default -> throw new BusinessException(ResultCode.CONFLICT, "sold out");
        }

        // 4. Compute total from DB prices.
        Map<Long, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        int totalInCents = Money.subtotalCents(byId, qtyByProduct);

        // 5. LPUSH to orders:pending + set order_status = queued, return 202.
        Long orderId = ORDER_SEQ.incrementAndGet();
        String payload = jsonPayload(orderId, userId, request, totalInCents, qtyByProduct, useCoins, validatedCoinAmount);
        redis.opsForList().leftPush(RedisKeys.ordersPending(), payload);
        redis.opsForValue().set(RedisKeys.orderStatus(orderId), "queued");
        return new CheckoutResponse(orderId, "queued");
    }

    /**
     * Serialize the durable-write context as JSON for the consumer.
     * Carries everything the consumer needs to insert the Order + OrderItems
     * without re-fetching from the DB (except for the DB-backstop stock decrement).
     */
    private String jsonPayload(Long orderId, Long userId, CheckoutRequest req,
                                int total, Map<Long, Integer> items, boolean useCoins, int coinAmount) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("orderId", orderId);
            node.put("userId", userId);
            node.put("customerName", req.getCustomerName());
            node.put("customerEmail", req.getCustomerEmail());
            node.put("customerPhone", req.getCustomerPhone());
            node.put("paymentMethod", req.getPaymentMethod().toUpperCase());
            node.put("shippingAddress", req.getShippingAddress());
            node.put("totalInCents", total);
            node.put("idempotencyKey", req.getIdempotencyKey());
            node.put("useCoins", useCoins);
            node.put("coinAmount", coinAmount);
            var itemsNode = node.putArray("items");
            items.forEach((pid, qty) -> {
                var i = itemsNode.addObject();
                i.put("productId", pid);
                i.put("quantity", qty);
            });
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
