package com.iflytek.frame.service;

import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatMessageMapper;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.impl.ChatServiceImpl;
import com.iflytek.frame.websocket.AdminPresenceRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatServiceImplDeleteTest {

    @Test
    void ownerCanDeleteSession() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AdminPresenceRegistry presence = mock(AdminPresenceRegistry.class);
        ChatSession s = new ChatSession();
        s.setId(5L); s.setUserId(7L); s.setStatus("OPEN");
        when(sessionMapper.selectById(5L)).thenReturn(s);

        ChatServiceImpl svc = new ChatServiceImpl(sessionMapper, messageMapper, presence);
        svc.deleteSession(5L, 7L);

        verify(sessionMapper).updateById(any(ChatSession.class));
    }

    @Test
    void nonOwnerCannotDelete() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        AdminPresenceRegistry presence = mock(AdminPresenceRegistry.class);
        ChatSession s = new ChatSession();
        s.setId(5L); s.setUserId(7L); s.setStatus("OPEN");
        when(sessionMapper.selectById(5L)).thenReturn(s);

        ChatServiceImpl svc = new ChatServiceImpl(sessionMapper, messageMapper, presence);
        assertThatThrownBy(() -> svc.deleteSession(5L, 999L))
                .isInstanceOf(BusinessException.class);
        verify(sessionMapper, never()).updateById(any(ChatSession.class));
    }
}
