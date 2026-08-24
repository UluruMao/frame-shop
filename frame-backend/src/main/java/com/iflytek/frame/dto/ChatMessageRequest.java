package com.iflytek.frame.dto;

import lombok.Data;

@Data
public class ChatMessageRequest {
    private String content;
    private String imageUrl;
}
