package com.iflytek.frame.service;

import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.dto.ChatMessageVO;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.util.PageQuery;

import java.util.List;

public interface ChatService {
    ChatSessionVO findOrCreateOpenSession(Long userId);
    ChatMessageVO sendUserMessage(Long sessionId, Long userId, String content, String imageUrl);
    ChatMessageVO sendAdminMessage(Long sessionId, Long adminId, String content, String imageUrl);
    /** User-facing history: ownership-checked before paginating. */
    PageResult<ChatMessageVO> historyForUser(Long sessionId, Long userId, PageQuery query);
    /** Admin-facing history: no ownership check (admins can read any session). */
    PageResult<ChatMessageVO> history(Long sessionId, PageQuery query);
    List<ChatSessionVO> openSessions(); // admin inbox
    /** Soft-delete a session (status=DELETED), owner-only. Hidden from both inboxes; messages retained. */
    void deleteSession(Long sessionId, Long userId);
}
