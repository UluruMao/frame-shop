package com.iflytek.frame.config;

import com.iflytek.frame.dto.ChatMessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 3 — the outbound interceptor that routes chat-topic publishes through
 * Redis pub/sub instead of the in-JVM simple broker.
 *
 * <p>Installed on the {@code clientOutboundChannel} (see
 * {@link RedisChatBrokerConfig}). When the broker is about to deliver a message
 * to {@code /topic/session/{sessionId}} (the path {@code ChatStompController}
 * publishes to), this interceptor:
 * <ol>
 *   <li>publishes the payload (serialized {@link ChatMessageVO}) to the Redis
 *       channel {@code chat:session:{sessionId}} — every instance's
 *       {@link RedisChatBrokerRelay} receives it and re-broadcasts to its own
 *       local WS subscribers (cross-instance fan-out); and</li>
 *   <li>returns {@code null} to drop the in-JVM delivery (the local subscribers
 *       will receive it via the relay's re-broadcast loopback, same as every
 *       other instance — uniform delivery, no double-send on the source).</li>
 * </ol>
 *
 * <p>The {@link RedisChatBrokerRelay#REBROADCASTING} ThreadLocal breaks the
 * loop: when the relay re-broadcasts (flag set), this interceptor lets the
 * message pass through to the local broker unchanged — so the re-broadcast
 * reaches this instance's own subscribers via the in-JVM broker, exactly once.
 *
 * <p>Only {@code MESSAGE} frames headed to {@code /topic/session/} are
 * intercepted; SUBSCRIBE/ACK/etc. and other destinations pass through.
 */
@RequiredArgsConstructor
class RedisChatBrokerInterceptor implements ChannelInterceptor {

    /** STOMP destination prefix for per-session chat topics. */
    private static final String SESSION_TOPIC_PREFIX = "/topic/session/";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Relay re-broadcasts (flag set on the listener thread) pass straight
        // through to the local broker — this is the fan-in half.
        if (Boolean.TRUE.equals(RedisChatBrokerRelay.REBROADCASTING.get())) {
            return message;
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        // Only intercept outbound MESSAGE deliveries to session topics (the
        // pushes the controller originates). SUBSCRIBE receipts, ERROR frames,
        // etc. are untouched.
        if (accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(SESSION_TOPIC_PREFIX)) {
            return message;
        }
        String sessionId = destination.substring(SESSION_TOPIC_PREFIX.length());
        if (sessionId.isEmpty()) {
            return message; // malformed — let the broker deal with it
        }
        // Publish the payload to the Redis channel for cross-instance fan-out.
        // The body is the JSON of the ChatMessageVO, so every instance's relay
        // can deserialize and re-broadcast the same object to its local subscribers.
        Object payload = message.getPayload();
        String body;
        try {
            if (payload instanceof byte[] bytes) {
                body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } else if (payload instanceof String s) {
                body = s;
            } else {
                body = objectMapper.writeValueAsString(payload);
            }
        } catch (Exception e) {
            System.getLogger(RedisChatBrokerInterceptor.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "failed to serialize chat message for Redis publish on " + destination, e);
            return message; // fall back to in-JVM delivery rather than drop
        }
        redisTemplate.convertAndSend(
                RedisChatBrokerRelay.CHANNEL_PREFIX + sessionId, body);
        // Drop the in-JVM delivery: the relay's loopback re-broadcast (this
        // instance's own subscribers) + the other instances' relays cover all
        // subscribers uniformly.
        return null;
    }
}
