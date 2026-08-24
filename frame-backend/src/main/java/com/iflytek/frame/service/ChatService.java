package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ChatMessageVO;
import com.iflytek.frame.dto.ChatSessionVO;

import java.util.List;

public interface ChatService {
    ChatSessionVO findOrCreateOpenSession(Long userId);
    ChatMessageVO sendUserMessage(Long sessionId, Long userId, String content, String imageUrl);
    ChatMessageVO sendAdminMessage(Long sessionId, Long adminId, String content, String imageUrl);
    PageResult<ChatMessageVO> history(Long sessionId, int page, int size);
    List<ChatSessionVO> openSessions(); // admin inbox
}
