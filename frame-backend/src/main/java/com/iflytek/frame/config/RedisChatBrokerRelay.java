package com.iflytek.frame.config;

import com.iflytek.frame.dto.ChatMessageVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 3 — the Redis pub/sub → local WS relay.
 *
 * <p>One instance is the source of a chat message: {@code ChatStompController}
 * publishes it to {@code /topic/session/{sessionId}}; the
 * {@link RedisChatBrokerInterceptor} on the client-outbound channel intercepts
 * that publish and redirects it to the Redis channel
 * {@code chat:session:{sessionId}} instead of delivering in-JVM. That achieves
 * Redis fan-out: every instance receives the message.
 *
 * <p>This relay is the fan-in half: every instance subscribes to the pattern
 * {@code chat:session:*} (wired in {@link RedisChatBrokerConfig}). When a
 * pub/sub message arrives on {@code chat:session:{sessionId}}, the relay parses
 * the session id from the channel name, deserializes the body (the serialized
 * {@link ChatMessageVO}) and re-broadcasts it to its own local WS subscribers via
 * {@code simpMessagingTemplate.convertAndSend("/topic/session/{sessionId}", vo)}.
 *
 * <p>The re-broadcast is guarded by the {@link #REBROADCASTING} ThreadLocal so
 * the interceptor lets it pass through to the local in-JVM broker (which
 * delivers to the instance's own subscribers) instead of re-publishing to Redis
 * — this is the loop-break that keeps a single publish from cycling forever.
 * The Redis pub/sub listener thread is single-threaded per subscription, so a
 * ThreadLocal flag is sufficient and avoids stamping/inspecting message headers
 * (which would pull in the SimpMessagingTemplate conversion machinery).
 *
 * <p>{@link SimpMessagingTemplate} is resolved lazily (via {@link ObjectProvider})
 * to break a bean-creation cycle: {@code WebSocketConfig} needs the interceptor
 * (a bean of {@code RedisChatBrokerConfig}), and {@code RedisChatBrokerConfig}
 * needs {@code SimpMessagingTemplate} — which is itself created by the WebSocket
 * message-broker config that drives {@code WebSocketConfig}. Resolving the
 * template lazily (only inside {@code onMessage}, by which time the context is
 * fully initialized) breaks that cycle cleanly.
 *
 * <p>Persist-before-publish is unchanged: the controller publishes only after
 * {@code ChatServiceImpl.persistMessage} inserted the row, so a message reaches
 * the topic iff it was persisted first — that ordering is preserved by leaving
 * the controller untouched (this task only swaps the broker backing).
 */
class RedisChatBrokerRelay implements MessageListener {

    /** Redis pub/sub channel prefix; the suffix is the session id. */
    static final String CHANNEL_PREFIX = "chat:session:";

    /**
     * Set on the listener thread while the relay is re-broadcasting a pub/sub
     * message to the local broker. {@link RedisChatBrokerInterceptor} checks
     * this and, when set, lets the re-broadcast pass to the local broker
     * instead of re-publishing to Redis — the loop-break.
     */
    static final ThreadLocal<Boolean> REBROADCASTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ObjectProvider<SimpMessagingTemplate> templateProvider;
    private final ObjectMapper objectMapper;

    RedisChatBrokerRelay(ObjectProvider<SimpMessagingTemplate> templateProvider,
                         ObjectMapper objectMapper) {
        this.templateProvider = templateProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
        String sessionId = parseSessionId(channel);
        if (sessionId == null) {
            return; // not a chat session channel — ignore (defensive)
        }
        // Resolve the template lazily — by the time a pub/sub message arrives
        // the context is fully initialized, so this never hits the eager
        // bean-creation cycle that constructor injection would.
        SimpMessagingTemplate simpMessagingTemplate = templateProvider.getIfAvailable();
        if (simpMessagingTemplate == null) {
            // Context not ready (shouldn't happen — the listener container
            // starts after the context) — drop rather than NPE.
            System.getLogger(RedisChatBrokerRelay.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "SimpMessagingTemplate not available; dropping chat message on " + channel);
            return;
        }
        String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            ChatMessageVO vo = objectMapper.readValue(body, ChatMessageVO.class);
            // Re-broadcast to the local in-JVM broker, which delivers to this
            // instance's own WS subscribers. The REBROADCASTING flag tells the
            // interceptor this is a fan-in re-broadcast (not an original publish)
            // — let it pass to the broker instead of re-publishing to Redis
            // (otherwise the re-broadcast would re-enter the outbound channel,
            // hit the interceptor, and loop).
            REBROADCASTING.set(Boolean.TRUE);
            try {
                simpMessagingTemplate.convertAndSend("/topic/session/" + sessionId, vo);
            } finally {
                REBROADCASTING.remove();
            }
        } catch (Exception e) {
            // A malformed body shouldn't take down the listener thread — log and
            // drop; the message was already persisted (persist-before-publish).
            System.getLogger(RedisChatBrokerRelay.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "failed to relay chat message on " + channel, e);
        }
    }

    /** Channel is {@code chat:session:{id}} → returns {@code {id}} (as a string
     *  so non-numeric session ids, if any, still route — the destination is the
     *  same textual id). */
    private String parseSessionId(String channel) {
        if (!channel.startsWith(CHANNEL_PREFIX)) {
            return null;
        }
        return channel.substring(CHANNEL_PREFIX.length());
    }
}
