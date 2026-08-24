package com.iflytek.frame.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.iflytek.frame.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A single message in a {@link ChatSession} (UC011). {@code senderType} is USER or ADMIN;
 * {@code content} is required, {@code imageUrl} is an optional attachment (e.g.
 * /uploads/chat/...). Append-only — messages are never edited or deleted.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {
    private Long sessionId;
    /** USER / ADMIN */
    private String senderType;
    private Long senderId;
    private String content;
    private String imageUrl;
}
