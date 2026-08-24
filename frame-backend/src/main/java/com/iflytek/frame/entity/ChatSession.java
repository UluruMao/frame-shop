package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A live-chat session between a user and a customer-service admin (UC011). Created OPEN;
 * the assigned admin closes it (status = CLOSED) when the conversation ends. One open
 * session per user at a time is enforced by the service layer.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_session")
public class ChatSession extends BaseEntity {
    private Long userId;
    private Long adminId;
    /** OPEN / CLOSED */
    private String status;
}
