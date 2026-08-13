package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("order_item")
public class OrderItem extends BaseEntity {
    private Long orderId;
    private Long productId;
    private String name;        // snapshot
    private int priceInCents;   // snapshot
    private int quantity;
}
