package com.iflytek.frame.websocket;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.mapper.ChatSessionMapper;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.security.SecurityUser;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthChannelInterceptorTest {

    @Mock JwtUtil jwtUtil;
    @Mock AdminPresenceRegistry presenceRegistry;
    @Mock ChatSessionMapper sessionMapper;
    @Mock MessageChannel channel;
    @InjectMocks AuthChannelInterceptor interceptor;

    @Test
    void connect_validToken_stampsPrincipalAndRegistersAdmin() {
        Long adminId = 42L;
        String token = "valid.jwt";
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(adminId));
        when(claims.get("username")).thenReturn("admin");
        when(claims.get("role")).thenReturn("ADMIN");
        when(jwtUtil.getHeader()).thenReturn("Authorization");
        when(jwtUtil.getTokenPrefix()).thenReturn("Bearer ");
        when(jwtUtil.parse(token)).thenReturn(claims);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        // leave mutable so preSend can stamp the principal via setUser; mirrors the
        // real STOMP pipeline where inbound accessors stay mutable for interceptors.
        accessor.setLeaveMutable(true);
        // build via createMessage so the accessor reference is preserved and
        // StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class) (the
        // production pattern) finds it; setHeaders(accessor) copies header values
        // only and leaves getAccessor returning null.
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, channel);

        assertNotNull(result);
        StompHeaderAccessor out = StompHeaderAccessor.wrap(result);
        Object principal = out.getUser();
        assertNotNull(principal, "principal should be stamped on CONNECT");
        assertInstanceOf(SecurityUser.class, principal);
        assertEquals(adminId, ((SecurityUser) principal).userId());
        verify(presenceRegistry).onAdminConnect(adminId); // ADMIN role → register presence
    }

    @Test
    void connect_invalidToken_throws() {
        when(jwtUtil.getHeader()).thenReturn("Authorization");
        when(jwtUtil.getTokenPrefix()).thenReturn("Bearer ");
        when(jwtUtil.parse("bad")).thenReturn(null);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer bad");
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThrows(org.springframework.messaging.MessagingException.class,
                () -> interceptor.preSend(msg, channel));
    }

    @Test
    void nonConnectMessage_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(msg, channel);
        assertSame(msg, result);
    }

    @Test
    void disconnect_adminPrincipal_decrementsPresence() {
        Long adminId = 7L;
        SecurityUser admin = new SecurityUser(adminId, "admin", Role.ADMIN,
                List.of(new SecurityUser.Authority("ROLE_ADMIN")));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        // principal is stamped on CONNECT in the real pipeline; a DISCONNECT with the
        // principal already attached is the case afterSendCompletion must handle.
        accessor.setLeaveMutable(true);
        accessor.setUser(admin);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.afterSendCompletion(msg, channel, true, null);

        verify(presenceRegistry).onAdminDisconnect(adminId);
    }

    @Test
    void disconnect_noPrincipal_isNoOp() {
        // DISCONNECT frame carrying no principal (the likely real case — Spring STOMP
        // does not always propagate the principal onto the DISCONNECT frame). The
        // decrement must be skipped, not throw.
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.afterSendCompletion(msg, channel, true, null);

        verify(presenceRegistry, never()).onAdminDisconnect(any());
    }

    // ---- SUBSCRIBE ownership checks (delivery fan-out IDOR closure) ----

    @Test
    void subscribe_ownerUser_isAllowed() {
        Long ownerId = 5L;
        SecurityUser owner = new SecurityUser(ownerId, "alice", Role.USER,
                List.of(new SecurityUser.Authority("ROLE_USER")));
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(ownerId);
        when(sessionMapper.selectById(7L)).thenReturn(session);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/session/7");
        accessor.setUser(owner);
        accessor.setLeaveMutable(true);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // returns the message (subscription proceeds); does not throw
        Message<?> result = interceptor.preSend(msg, channel);
        assertNotNull(result);
    }

    @Test
    void subscribe_nonOwnerUser_isRejected() {
        Long ownerId = 5L;
        Long intruderId = 999L;
        SecurityUser intruder = new SecurityUser(intruderId, "mallory", Role.USER,
                List.of(new SecurityUser.Authority("ROLE_USER")));
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(ownerId); // owner is 5, subscriber is 999
        when(sessionMapper.selectById(7L)).thenReturn(session);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/session/7");
        accessor.setUser(intruder);
        accessor.setLeaveMutable(true);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(org.springframework.messaging.MessagingException.class,
                () -> interceptor.preSend(msg, channel));
    }

    @Test
    void subscribe_admin_isAllowedOnAnySession() {
        Long adminId = 42L;
        Long otherUserId = 5L;
        SecurityUser admin = new SecurityUser(adminId, "admin", Role.ADMIN,
                List.of(new SecurityUser.Authority("ROLE_ADMIN")));
        ChatSession session = new ChatSession();
        session.setId(7L);
        session.setUserId(otherUserId); // admin is NOT the owner
        // admin path must not even hit the mapper — no stubbing needed; the
        // absence of a when(...) means any selectById call would throw (lenient
        // stubbing) — but it's never called, which is the point.

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/session/7");
        accessor.setUser(admin);
        accessor.setLeaveMutable(true);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(msg, channel);
        assertNotNull(result);
        verify(sessionMapper, never()).selectById(any()); // admin bypasses the lookup
    }

    @Test
    void subscribe_nonExistentSession_isRejected() {
        SecurityUser user = new SecurityUser(5L, "alice", Role.USER,
                List.of(new SecurityUser.Authority("ROLE_USER")));
        when(sessionMapper.selectById(99L)).thenReturn(null); // session doesn't exist

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/session/99");
        accessor.setUser(user);
        accessor.setLeaveMutable(true);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThrows(org.springframework.messaging.MessagingException.class,
                () -> interceptor.preSend(msg, channel));
    }
}
