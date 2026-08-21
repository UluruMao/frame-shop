package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import com.iflytek.frame.common.OrderStatus;
import com.iflytek.frame.common.PaymentMethod;
import com.iflytek.frame.common.RefundStatus;
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
    /** Actual amount paid after coin discount (UC005/UC012). Review reward = this / 100. */
    private int paidInCents;
    private OrderStatus status;
    private String idempotencyKey;
    private String customerPhone;
    private PaymentMethod paymentMethod;
    private String trackingNumber;
    private java.time.LocalDateTime shippedAt;
    private java.time.LocalDateTime deliveredAt;
    private java.time.LocalDateTime completedAt;
    private java.time.LocalDateTime paidAt;
    private RefundStatus refundStatus;
    private String refundReason;
    private String cancelReason;
}
