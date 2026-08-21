package com.iflytek.frame.websocket;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthChannelInterceptorTest {

    @Mock JwtUtil jwtUtil;
    @Mock AdminPresenceRegistry presenceRegistry;
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
}
