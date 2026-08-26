package com.iflytek.frame.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iflytek.frame.common.PageResult;
import com.iflytek.frame.common.ResultCode;
import com.iflytek.frame.dto.ChatMessageVO;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.entity.ChatMessage;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.exception.BusinessException;
import com.iflytek.frame.mapper.ChatMessageMapper;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.service.ChatService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.websocket.AdminPresenceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AdminPresenceRegistry presenceRegistry;

    @Override
    @Transactional
    public ChatSessionVO findOrCreateOpenSession(Long userId) {
        ChatSession session = findOpenSession(userId);
        if (session == null) {
            session = new ChatSession();
            session.setUserId(userId);
            session.setStatus("OPEN");
            // Left inline (not IdempotentInsert.insertOr): the catch re-selects and, if that still
            // returns null, rethrows the ORIGINAL DataIntegrityViolationException. insertOr's
            // onDuplicate Supplier can't access the caught exception, so it can't faithfully
            // rethrow the DB-vendor exception object on the (shouldn't-happen) null path.
            try {
                sessionMapper.insert(session);
            } catch (DataIntegrityViolationException e) {
                // Concurrent find-or-create raced: the unique index uk_session_open_user
                // (open_user_key = user_id when OPEN) rejected our duplicate. The other
                // transaction already created the session, so re-select and return it.
                session = findOpenSession(userId);
                if (session == null) {
                    throw e; // shouldn't happen, but fail loud rather than return null
                }
            }
        }
        return toSessionVO(session);
    }

    private ChatSession findOpenSession(Long userId) {
        return sessionMapper.selectOne(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getUserId, userId)
                        .eq(ChatSession::getStatus, "OPEN")
                        .last("LIMIT 1"));
    }

    @Override
    @Transactional
    public ChatMessageVO sendUserMessage(Long sessionId, Long userId, String content, String imageUrl) {
        ChatSession session = loadAndCheckOwnership(sessionId, userId);
        if (session.getAdminId() == null) {
            Long admin = presenceRegistry.nextOnlineAdmin();
            if (admin != null) {
                session.setAdminId(admin);
                sessionMapper.updateById(session);
            } // else: stays unassigned, message still persisted (fail-safe)
        }
        return persistMessage(session, "USER", userId, content, imageUrl);
    }

    @Override
    @Transactional
    public ChatMessageVO sendAdminMessage(Long sessionId, Long adminId, String content, String imageUrl) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat session not found: " + sessionId);
        }
        return persistMessage(session, "ADMIN", adminId, content, imageUrl);
    }

    @Override
    public PageResult<ChatMessageVO> historyForUser(Long sessionId, Long userId, PageQuery query) {
        // ownership check BEFORE paginating — closes the IDOR hole
        loadAndCheckOwnership(sessionId, userId);
        return history(sessionId, query);
    }

    @Override
    public PageResult<ChatMessageVO> history(Long sessionId, PageQuery query) {
        Page<ChatMessage> p = new Page<>(query.getCurrent(), query.getSize());
        Page<ChatMessage> result = messageMapper.selectPage(p,
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getCreateTime));
        List<ChatMessageVO> records = result.getRecords().stream().map(this::toMessageVO).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), records);
    }

    @Override
    public List<ChatSessionVO> openSessions() {
        List<ChatSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getStatus, "OPEN")
                        .orderByDesc(ChatSession::getUpdateTime));
        return sessions.stream().map(this::toSessionVO).toList();
    }

    private ChatSession loadAndCheckOwnership(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "chat session not found: " + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "not the owner of this chat session");
        }
        return session;
    }

    private ChatMessageVO persistMessage(ChatSession session, String senderType, Long senderId, String content, String imageUrl) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(session.getId());
        msg.setSenderType(senderType);
        msg.setSenderId(senderId);
        msg.setContent(content);
        msg.setImageUrl(imageUrl);
        msg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(msg);
        return toMessageVO(msg);
    }

    private ChatMessageVO toMessageVO(ChatMessage m) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(m.getId());
        vo.setSessionId(m.getSessionId());
        vo.setSenderType(m.getSenderType());
        vo.setSenderId(m.getSenderId());
        vo.setContent(m.getContent());
        vo.setImageUrl(m.getImageUrl());
        vo.setCreateTime(m.getCreateTime());
        return vo;
    }

    private ChatSessionVO toSessionVO(ChatSession s) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(s.getId());
        vo.setUserId(s.getUserId());
        vo.setAdminId(s.getAdminId());
        vo.setStatus(s.getStatus());
        vo.setCreateTime(s.getCreateTime());
        vo.setUpdateTime(s.getUpdateTime());
        return vo;
    }
}
