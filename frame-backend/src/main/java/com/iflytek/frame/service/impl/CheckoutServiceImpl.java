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
                throw new BusinessException(ResultCode.PARAM_INVALID, "金币数量必须大于 0");
            }
            if (requested > preview.getUsable()) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "金币数量超过可用抵扣额度");
            }
            validatedCoinAmount = requested;
        }

        // 2b/3. Flash sale is a DISCOUNT, not a purchase gate. Partition the cart into:
        //   - liveFlash: product.isFlashSale AND stock:{id} key exists in Redis (sale open).
        //     These go through the Redis Lua gate (burst-shield) + one-per-user seckill key,
        //     and get the discounted flash price.
        //   - dbOnly: everything else (non-flash, or flash-not-yet-open). No Redis gate — the
        //     consumer's DB-backstop decrementStock (WHERE stock >= qty) is the oversell
        //     authority. Sold at list price (no discount).
        // This lets shoppers always buy (non-flash + not-yet-open flash) without the gate
        // blocking them with "stock not initialized."
        Map<Long, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        List<Long> liveFlashIds = new ArrayList<>();
        List<Integer> liveFlashQtys = new ArrayList<>();
        for (Long pid : qtyByProduct.keySet()) {
            Product p = byId.get(pid);
            boolean saleLive = p != null && p.isFlashSale()
                    && Boolean.TRUE.equals(redis.hasKey(RedisKeys.stock(pid)));
            if (saleLive) {
                liveFlashIds.add(pid);
                liveFlashQtys.add(qtyByProduct.get(pid));
            }
        }

        // One-per-user seckill restriction applies ONLY to live flash items.
        for (Long pid : liveFlashIds) {
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.seckill(pid, userId)))) {
                throw new BusinessException(ResultCode.CONFLICT, "您已购买过此商品");
            }
        }

        // Redis Lua gate — only for live flash items (atomic all-or-nothing decrement).
        // Gap 3: catch Redis outage → 503 (fail-closed, never bypass the gate).
        if (!liveFlashIds.isEmpty()) {
            String claimResult;
            try {
                claimResult = stockGateService.tryClaimStock(liveFlashIds, liveFlashQtys);
            } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
                throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "库存系统暂时不可用，请稍后再试");
            }
            switch (claimResult) {
                case "OK" -> { /* proceed — flash stock atomically decremented in Redis */ }
                case "NOT_INITIALIZED" -> throw new BusinessException(
                        ResultCode.SERVICE_UNAVAILABLE, "秒杀尚未开始，请稍后再试");
                default -> throw new BusinessException(ResultCode.CONFLICT, "商品已售罄");
            }
        }

        // 4. Compute total. Flash discount applies ONLY to live-flash items (saleLive);
        // non-flash + not-yet-open flash sell at list price. Money.subtotalCentsFlashLive
        // takes the set of live-flash ids so it discounts exactly those.
        int totalInCents = Money.subtotalCentsFlashLive(byId, qtyByProduct,
                new HashSet<>(liveFlashIds));

        // 5. LPUSH to orders:pending + set order_status = queued, return 202.
        Long orderId = ORDER_SEQ.incrementAndGet();
        String payload = jsonPayload(orderId, userId, request, totalInCents, qtyByProduct,
                useCoins, validatedCoinAmount, new HashSet<>(liveFlashIds));
        redis.opsForList().leftPush(RedisKeys.ordersPending(), payload);
        redis.opsForValue().set(RedisKeys.orderStatus(orderId), "queued");
        return new CheckoutResponse(orderId, "queued");
    }

    /**
     * Serialize the durable-write context as JSON for the consumer.
     * Carries everything the consumer needs to insert the Order + OrderItems
     * without re-fetching from the DB (except for the DB-backstop stock decrement).
     * {@code liveFlashIds} tells the consumer which items get the flash-discount snapshot
     * price (matching what the customer paid).
     */
    private String jsonPayload(Long orderId, Long userId, CheckoutRequest req,
                                int total, Map<Long, Integer> items, boolean useCoins, int coinAmount,
                                Set<Long> liveFlashIds) {
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
            var liveFlashNode = node.putArray("liveFlashIds");
            liveFlashIds.forEach(liveFlashNode::add);
            var itemsNode = node.putArray("items");
            items.forEach((pid, qty) -> {
                var i = itemsNode.addObject();
                i.put("productId", pid);
                i.put("quantity", qty);
            });
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "serialization failed");
        }
    }
}
