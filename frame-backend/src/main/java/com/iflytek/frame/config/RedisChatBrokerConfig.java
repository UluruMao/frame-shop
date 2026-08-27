package com.iflytek.frame.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Task 3 — wires the Redis pub/sub chat broker relay.
 *
 * <p>Replaces the in-memory simple broker as the cross-instance delivery
 * mechanism for chat topic messages: a chat message published to
 * {@code /topic/session/{sessionId}} on instance A is intercepted by
 * {@link RedisChatBrokerInterceptor} (added to the client-outbound channel in
 * {@link WebSocketConfig}) and published to the Redis channel
 * {@code chat:session:{sessionId}}; every instance's
 * {@link RedisChatBrokerRelay} (subscribed to {@code chat:session:*} via the
 * {@link RedisMessageListenerContainer} below) receives it and re-broadcasts to
 * its own local WS subscribers. Net: a message published on instance A reaches
 * WS subscribers on instance B.
 *
 * <p>{@link WebSocketConfig} keeps a simple broker on {@code /topic} as the
 * local fan-in sink — the relay's re-broadcast goes through it to reach this
 * instance's own subscribers. The interceptor ensures the controller's original
 * publishes go to Redis (not the in-JVM broker), so the simple broker is no
 * longer the cross-instance mechanism — Redis is.
 *
 * <p>The relay resolves {@link SimpMessagingTemplate} lazily (via
 * {@link ObjectProvider}) to break the bean-creation cycle between this config
 * (which needs the template) and {@code WebSocketConfig} (which needs the
 * interceptor bean defined here). See {@link RedisChatBrokerRelay} for details.
 */
@Configuration
public class RedisChatBrokerConfig {

    /** Redis pub/sub channel pattern: matches every {@code chat:session:{id}}. */
    public static final String CHANNEL_PATTERN = "chat:session:*";

    /** The relay: receives Redis pub/sub messages and re-broadcasts to local WS
     *  subscribers. The {@link SimpMessagingTemplate} is injected lazily (via
     *  {@link ObjectProvider}) to avoid a bean-creation cycle with the WebSocket
     *  config that drives the template's own creation. */
    @Bean
    public RedisChatBrokerRelay redisChatBrokerRelay(
            ObjectProvider<SimpMessagingTemplate> templateProvider,
            ObjectMapper objectMapper) {
        return new RedisChatBrokerRelay(templateProvider, objectMapper);
    }

    /** The outbound interceptor: routes {@code /topic/session/**} publishes to
     *  Redis pub/sub. Defined here as a bean; {@code WebSocketConfig} injects it
     *  and registers it on the client-outbound channel (channel interceptors
     *  must be added in {@code configureClientOutboundChannel}). It depends only
     *  on {@link StringRedisTemplate} + {@link ObjectMapper} (not the messaging
     *  template), so it doesn't extend the cycle. */
    @Bean
    public RedisChatBrokerInterceptor redisChatBrokerInterceptor(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        return new RedisChatBrokerInterceptor(redisTemplate, objectMapper);
    }

    /** Subscribes the relay to {@code chat:session:*} (pattern subscription so
     *  any session id routes — no enumeration). Spring's container manages the
     *  subscription lifecycle (connect/reconnect) and drives the relay's
     *  {@code onMessage} on a dedicated listener thread. */
    @Bean
    public RedisMessageListenerContainer redisChatListenerContainer(
            RedisConnectionFactory connectionFactory, RedisChatBrokerRelay relay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(relay, new PatternTopic(CHANNEL_PATTERN));
        return container;
    }
}
