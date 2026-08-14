package com.iflytek.frame.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import com.iflytek.frame.service.StockGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public CheckoutResponse checkout(CheckoutRequest request, Long userId) {
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

        // 3. Redis Lua gate (atomic, all-or-nothing — no partial decrements on FAIL).
        List<Long> ids = new ArrayList<>(qtyByProduct.keySet());
        List<Integer> qtys = ids.stream().map(qtyByProduct::get).collect(Collectors.toList());
        boolean claimed = stockGateService.tryClaimStock(ids, qtys);
        if (!claimed) {
            throw new BusinessException(ResultCode.CONFLICT, "sold out");
        }

        // 4. Compute total from DB prices.
        int totalInCents = 0;
        Map<Long, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            totalInCents += byId.get(e.getKey()).getPriceInCents() * e.getValue();
        }

        // 5. XADD to orders:pending + set order_status = queued, return 202.
        //    orderId is generated now (correlates the stream message to the queued-status key).
        //    NOTE: System.currentTimeMillis() is simplistic and unsafe under concurrency
        //    (collisions if two checkouts happen within the same millisecond). A Snowflake
        //    or UUID is the production fix — noted in the task report.
        Long orderId = System.currentTimeMillis();
        String payload = jsonPayload(orderId, userId, request, totalInCents, qtyByProduct);
        redis.opsForStream().add("orders:pending", Map.of(
                "orderId", String.valueOf(orderId),
                "userId", String.valueOf(userId),
                "payload", payload));
        redis.opsForValue().set("order_status:" + orderId, "queued");
        return new CheckoutResponse(orderId, "queued");
    }

    /**
     * Serialize the durable-write context as JSON for the consumer.
     * Carries everything the consumer needs to insert the Order + OrderItems
     * without re-fetching from the DB (except for the DB-backstop stock decrement).
     */
    private String jsonPayload(Long orderId, Long userId, CheckoutRequest req,
                                int total, Map<Long, Integer> items) {
        try {
            var node = objectMapper.createObjectNode();
            node.put("orderId", orderId);
            node.put("userId", userId);
            node.put("customerName", req.getCustomerName());
            node.put("customerEmail", req.getCustomerEmail());
            node.put("shippingAddress", req.getShippingAddress());
            node.put("totalInCents", total);
            node.put("idempotencyKey", req.getIdempotencyKey());
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
