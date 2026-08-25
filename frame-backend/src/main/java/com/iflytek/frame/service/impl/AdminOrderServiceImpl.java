package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.PageResults;
import com.iflytek.frame.common.RefundStatus;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.AdminOrderService;
import com.iflytek.frame.service.OrderStockReleaseService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Admin-side order management implementation.
 *
 * <p>Every state transition is a CAS ({@code update...If/from/to}) returning rows-affected;
 * a 0 means the order was no longer in the expected state (concurrent mutation) → CONFLICT.
 * {@code approveRefund} recredits stock via the shared {@link OrderStockReleaseService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminOrderServiceImpl implements AdminOrderService {

    private final OrderMapper orderMapper;
    private final OrderStockReleaseService orderStockReleaseService;
    private final com.iflytek.frame.service.CoinService coinService;

    @Override
    public PageResult<Order> listOrders(PageQuery q, OrderStatus status) {
        Page<Order> page = new Page<>(q.getCurrent(), q.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(status != null, Order::getStatus, status)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResults.of(result);
    }

    @Override
    @Transactional
    public void ship(Long id, String trackingNumber) {
        int rows = orderMapper.updateStatusAndTracking(id, OrderStatus.PAID, OrderStatus.SHIPPED,
                trackingNumber, LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be shipped in current state");
        }
    }

    @Override
    @Transactional
    public void deliver(Long id) {
        int rows = orderMapper.updateStatusAndDelivered(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
                LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be delivered in current state");
        }
    }

    @Override
    public PageResult<Order> refundRequests(PageQuery q) {
        Page<Order> page = new Page<>(q.getCurrent(), q.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getStatus, OrderStatus.REFUND_PENDING)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResults.of(result);
    }

    @Override
    @Transactional
    public void approveRefund(Long id) {
        Order o = orderMapper.selectById(id);
        if (o == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        int rows = orderMapper.updateStatusAndRefund(id, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED,
                RefundStatus.APPROVED, null);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be refunded in current state");
        }
        // The item is now confirmed returned — recredit the stock.
        o.setStatus(OrderStatus.REFUNDED);
        orderStockReleaseService.release(o);
        try {
            coinService.refundCoins(id);
        } catch (Exception e) {
            log.warn("refundCoins({}) failed after approve-refund: {}", id, e.getMessage());
        }
    }

    @Override
    @Transactional
    public void denyRefund(Long id) {
        int rows = orderMapper.updateStatusAndRefund(id, OrderStatus.REFUND_PENDING, OrderStatus.SHIPPED,
                RefundStatus.DENIED, null);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be denied refund in current state");
        }
        // No stock change — the order returns to SHIPPED.
    }
}
