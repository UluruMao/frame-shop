package com.iflytek.frame.websocket;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.security.JwtUtil;
import com.iflytek.frame.security.SecurityUser;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame: reads the JWT from the Authorization
 * header (the browser WS API can't set headers, so the client passes it in the
 * CONNECT frame), validates it, and stamps a SecurityUser principal onto the
 * accessor. Admin connections are registered with AdminPresenceRegistry for
 * round-robin chat assignment.
 */
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final AdminPresenceRegistry presenceRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message; // only auth on CONNECT
        }
        String header = accessor.getFirstNativeHeader(jwtUtil.getHeader()); // "Authorization"
        if (header == null || !header.startsWith(jwtUtil.getTokenPrefix())) {
            throw new MessagingException("missing or malformed Authorization header");
        }
        String token = header.substring(jwtUtil.getTokenPrefix().length()).trim();
        Claims claims = jwtUtil.parse(token);
        if (claims == null) {
            throw new MessagingException("invalid or expired JWT");
        }
        SecurityUser principal = SecurityUser.from(claims);
        accessor.setUser(principal);
        if (principal.role() == Role.ADMIN) {
            presenceRegistry.onAdminConnect(principal.userId());
        }
        return message;
    }

    @Override
    public void afterSendCompletion(Message<?> message, MessageChannel channel, boolean sent, Exception ex) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            SecurityUser principal = (SecurityUser) accessor.getUser();
            if (principal != null && principal.role() == Role.ADMIN) {
                presenceRegistry.onAdminDisconnect(principal.userId());
            }
        }
    }
}
