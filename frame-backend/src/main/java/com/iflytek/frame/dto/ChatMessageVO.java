package com.iflytek.frame.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * A chat message, as returned to the client (UC011). Mirrors {@code ChatMessage} but is a
 * plain transfer object so the API can evolve independently of the entity.
 */
@Data
public class ChatMessageVO {
    private Long id;
    private Long sessionId;
    private String senderType;   // USER / ADMIN
    private Long senderId;
    private String content;
    private String imageUrl;
    private LocalDateTime createTime;
}
