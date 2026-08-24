package com.iflytek.frame.service.impl;

import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.entity.ChatMessage;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.mapper.ChatMessageMapper;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.websocket.AdminPresenceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock ChatSessionMapper sessionMapper;
    @Mock ChatMessageMapper messageMapper;
    @Mock AdminPresenceRegistry presenceRegistry;
    @InjectMocks ChatServiceImpl chatService;

    @Test
    void findOrCreateOpenSession_returnsExisting() {
        ChatSession existing = new ChatSession();
        existing.setId(7L);
        existing.setUserId(5L);
        existing.setStatus("OPEN");
        when(sessionMapper.selectOne(any())).thenReturn(existing);

        ChatSessionVO result = chatService.findOrCreateOpenSession(5L);

        assertEquals(7L, result.getId());
        verify(sessionMapper, never()).insert(any(ChatSession.class));
    }

    @Test
    void sendUserMessage_assignsAdminIfNoneAndOneOnline() {
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(5L);
        session.setAdminId(null);
        session.setStatus("OPEN");
        when(sessionMapper.selectById(7L)).thenReturn(session);
        when(presenceRegistry.nextOnlineAdmin()).thenReturn(99L);

        chatService.sendUserMessage(7L, 5L, "hello", null);

        // admin should be assigned
        verify(sessionMapper).updateById(org.mockito.ArgumentMatchers.<ChatSession>argThat(s -> Long.valueOf(99L).equals(s.getAdminId())));
        // message persisted as USER
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.<ChatMessage>argThat(m -> "USER".equals(m.getSenderType()) && "hello".equals(m.getContent())));
    }

    @Test
    void sendUserMessage_noAdminOnline_leavesUnassigned() {
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(5L);
        session.setAdminId(null);
        session.setStatus("OPEN");
        when(sessionMapper.selectById(7L)).thenReturn(session);
        when(presenceRegistry.nextOnlineAdmin()).thenReturn(null);

        chatService.sendUserMessage(7L, 5L, "hello", null);

        verify(sessionMapper, never()).updateById(any(ChatSession.class)); // no assignment
        verify(messageMapper).insert(any(ChatMessage.class)); // but the message IS persisted (fail-safe)
    }

    @Test
    void sendUserMessage_wrongUser_throws() {
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(5L); // owner is 5
        session.setStatus("OPEN");
        when(sessionMapper.selectById(7L)).thenReturn(session);
        assertThrows(com.iflytek.frame.exception.BusinessException.class,
                () -> chatService.sendUserMessage(7L, 999L, "intruder", null));
    }
}
