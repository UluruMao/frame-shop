package com.iflytek.frame.config;

import com.iflytek.frame.security.CorsProperties;
import com.iflytek.frame.websocket.AuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final AuthChannelInterceptor authChannelInterceptor;
    private final CorsProperties corsProperties;
    /** Routes /topic/session/** publishes to Redis pub/sub (Task 3). Registered
     *  on the client-outbound channel (interceptors are channel-level). */
    private final RedisChatBrokerInterceptor redisChatBrokerInterceptor;

    @Override
    public void configureMessageBroker(org.springframework.messaging.simp.config.MessageBrokerRegistry registry) {
        // A simple broker on /topic is retained as the LOCAL fan-in sink: the
        // RedisChatBrokerRelay re-broadcasts received pub/sub messages through
        // it to reach this instance's own WS subscribers. The
        // redisChatBrokerInterceptor on the client-outbound channel ensures the
        // controller's ORIGINAL publishes to /topic/session/** go to Redis
        // (cross-instance fan-out) instead of being delivered in-JVM — so the
        // simple broker is no longer the cross-instance mechanism (Redis is),
        // it merely delivers the local relay's re-broadcasts to local clients.
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns(corsProperties.getAllowedOrigins().split(","))
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        // Intercept outbound MESSAGE frames to /topic/session/** → publish to
        // Redis chat:session:{id} (cross-instance). Relay re-broadcasts (flagged
        // via RedisChatBrokerRelay.REBROADCASTING) pass through to the local
        // broker. Installed AFTER construction so the bean is resolved.
        registration.interceptors(redisChatBrokerInterceptor);
    }
}
