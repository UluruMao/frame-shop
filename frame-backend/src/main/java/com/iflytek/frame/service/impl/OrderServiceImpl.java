package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.OrderMapper;
import com.iflytek.frame.service.OrderService;
import com.iflytek.frame.util.PageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;

    @Override
    public Order getById(Long id, Long userId) {
        Order o = orderMapper.selectById(id);
        if (o == null || !o.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "order not found");
        }
        return o;
    }

    @Override
    public PageResult<Order> myOrders(Long userId, PageQuery query) {
        Page<Order> page = new Page<>(query.getCurrent(), query.getSize());
        LambdaQueryWrapper<Order> w = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);
        var result = orderMapper.selectPage(page, w);
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }

    @Override
    public void cancel(Long id, Long userId) {
        getById(id, userId);
        // atomic: only PENDING can be cancelled
        int rows = orderMapper.updateStatusIf(id, OrderStatus.PENDING, OrderStatus.CANCELLED);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order cannot be cancelled in current state");
        }
    }

    @Override
    public OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to) {
        int rows = orderMapper.updateStatusIf(id, from, to);
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "order state transition not allowed");
        }
        return to;
    }
}
