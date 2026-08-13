package com.iflytek.frame.service;

import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.entity.Order;
import com.iflytek.frame.util.PageQuery;

public interface OrderService {
    Order getById(Long id, Long userId);
    PageResult<Order> myOrders(Long userId, PageQuery query);
    void cancel(Long id, Long userId);
    OrderStatus flipStatus(Long id, OrderStatus from, OrderStatus to);  // atomic conditional update
}
