package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "Order")
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orders")
public class Order extends BaseEntity {
    private Long userId;
    private String customerName;
    private String customerEmail;
    private String shippingAddress;
    private int totalInCents;
    private OrderStatus status;
    private String idempotencyKey;
}
