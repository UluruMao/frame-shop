package com.iflytek.frame.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * A chat session, as returned to the client (UC011). Mirrors {@code ChatSession} but is a
 * plain transfer object so the API can evolve independently of the entity.
 */
@Data
public class ChatSessionVO {
    private Long id;
    private Long userId;
    private Long adminId;
    private String status;       // OPEN / CLOSED
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
