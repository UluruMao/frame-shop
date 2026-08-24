package com.iflytek.frame.websocket;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.entity.ChatSession;
import com.iflytek.frame.mapper.ChatSessionMapper;
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
 *
 * <p>Also authorizes SUBSCRIBE frames: a USER may only subscribe to
 * {@code /topic/session/{sessionId}} for a session they own; an ADMIN may
 * subscribe to any (the admin inbox needs that). This closes the
 * message-delivery fan-out leak adjacent to the REST-history IDOR — without it
 * any authenticated client could subscribe to any session's topic.
 */
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private static final String SESSION_TOPIC_PREFIX = "/topic/session/";

    private final JwtUtil jwtUtil;
    private final AdminPresenceRegistry presenceRegistry;
    /** Direct mapper (not ChatService) to avoid any broker→service cycle:
     *  the interceptor runs on the inbound channel, and ChatService transitively
     *  touches the messaging template. A mapper has no broker dependency. */
    private final ChatSessionMapper sessionMapper;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            return handleConnect(message, accessor);
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return handleSubscribe(message, accessor);
        }
        return message; // only CONNECT + SUBSCRIBE are intercepted
    }

    private Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
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

    private Message<?> handleSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        SecurityUser principal = (SecurityUser) accessor.getUser();
        if (principal == null) {
            // No principal means CONNECT auth never ran (or was rejected); block.
            throw new MessagingException("subscribe without an authenticated principal");
        }
        // Admins may subscribe to any session topic (admin inbox reads all sessions).
        if (principal.role() == Role.ADMIN) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(SESSION_TOPIC_PREFIX)) {
            return message; // not a session topic — let the broker decide
        }
        Long sessionId = parseSessionId(destination);
        if (sessionId == null) {
            throw new MessagingException("malformed session topic: " + destination);
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(principal.userId())) {
            // Don't reveal whether the session exists — same denial for not-found
            // and not-owner, mirroring the ownership-check posture in the service.
            throw new MessagingException("not authorized to subscribe to session " + sessionId);
        }
        return message;
    }

    private Long parseSessionId(String destination) {
        String tail = destination.substring(SESSION_TOPIC_PREFIX.length());
        try {
            return Long.valueOf(tail);
        } catch (NumberFormatException e) {
            return null;
        }
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
