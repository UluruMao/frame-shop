package com.iflytek.frame.service.impl;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.CheckoutRequest;
import com.iflytek.frame.dto.CheckoutResponse;
import com.iflytek.frame.dto.OrderItemDTO;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.entity.OrderItem;
import com.iflytek.frame.entity.Product;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderItemMapper;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.mapper.ProductMapper;
import com.iflytek.frame.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutServiceImpl implements CheckoutService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    @Override
    @Transactional
    public CheckoutResponse checkout(CheckoutRequest request, Long userId) {
        // 1. Aggregate quantities per product (dedup within the cart).
        //    LinkedHashMap preserves first-seen order so the rollback walk below
        //    can stop at the failing product deterministically.
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        for (OrderItemDTO item : request.getItems()) {
            qtyByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }

        // 2. Fetch products (server-side price authority — never trust the client).
        Map<Long, Product> byId = new HashMap<>();
        for (Long pid : qtyByProduct.keySet()) {
            Product p = productMapper.selectById(pid);
            if (p == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "one or more products not found");
            }
            byId.put(pid, p);
        }

        // 3. Synchronous DB conditional-update gate (the correctness core;
        //    Redis gate is added in Task 5 as an optimization on this correct base).
        //    Claim stock for each product; on a 0-rows failure reverse every claim
        //    already made in this invocation (the items decremented before this one).
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            int rows = productMapper.decrementStock(e.getKey(), e.getValue());
            if (rows == 0) {
                for (Map.Entry<Long, Integer> prev : qtyByProduct.entrySet()) {
                    if (prev.getKey().equals(e.getKey())) break;
                    productMapper.recreditStock(prev.getKey(), prev.getValue());
                }
                throw new BusinessException(ResultCode.CONFLICT, "sold out");
            }
        }

        // 4. Compute total from DB prices (price authority — client never sends price).
        int totalInCents = 0;
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            totalInCents += byId.get(e.getKey()).getPriceInCents() * e.getValue();
        }

        // 5. Insert the order (status=PENDING) guarded by the idempotency_key UNIQUE
        //    constraint, then the line items. Claim stock FIRST, persist only if the
        //    claim succeeded; on a duplicate insert reverse our decrement and return
        //    the existing order (no double order, no double decrement).
        Order order = new Order();
        order.setUserId(userId);
        order.setCustomerName(request.getCustomerName());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setShippingAddress(request.getShippingAddress());
        order.setTotalInCents(totalInCents);
        order.setStatus(OrderStatus.PENDING);
        order.setIdempotencyKey(request.getIdempotencyKey());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException dup) {
            // A prior delivery already created this order — reverse our decrement
            // and return the existing one.
            for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
                productMapper.recreditStock(e.getKey(), e.getValue());
            }
            Order existing = orderMapper.selectByIdempotencyKey(request.getIdempotencyKey());
            if (existing == null) {
                // The row vanished between the insert-failure and the lookup (concurrent
                // delete, DB blip). Don't NPE — re-throw as a CONFLICT so the @Transactional
                // rollback undoes our decrements cleanly and the failure surfaces as a proper
                // 409 rather than a 500-with-NPE. The recreditStock above is belt-and-suspenders
                // under the rollback.
                throw new BusinessException(ResultCode.CONFLICT,
                        "duplicate idempotency key but existing order not found");
            }
            return new CheckoutResponse(existing.getId(), "pending");
        }
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            Product p = byId.get(e.getKey());
            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getId());
            oi.setProductId(p.getId());
            oi.setName(p.getName());
            oi.setPriceInCents(p.getPriceInCents());
            oi.setQuantity(e.getValue());
            orderItemMapper.insert(oi);
        }
        return new CheckoutResponse(order.getId(), "pending");
    }
}
