package com.iflytek.frame.chat;

import com.iflytek.frame.common.Role;
import com.iflytek.frame.dto.ChatMessageRequest;
import com.iflytek.frame.dto.ChatMessageVO;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.entity.User;
import com.iflytek.frame.mapper.UserMapper;
import com.iflytek.frame.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * STOMP end-to-end integration test (UC011 real-time chat push).
 *
 * <p>The fetch-based e2e runner can't speak STOMP, so this is where the
 * real-time push guarantee is proven: a {@link WebSocketStompClient} connects
 * with the JWT carried in the CONNECT frame's {@code Authorization} header,
 * subscribes to {@code /topic/session/{sessionId}}, sends a message to
 * {@code /app/chat/{sessionId}}, and asserts the message is pushed to the
 * subscriber in real time AND persisted (fetched back via the REST history
 * endpoint). A second test exercises the Task 6 SUBSCRIBE ownership check: a
 * non-owner's SUBSCRIBE to a session topic is rejected.
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT)} against real Redis + MariaDB
 * (same posture as {@code CheckoutFlowIntegrationTest}). The production STOMP
 * endpoint {@code /ws-chat} is registered with SockJS, whose handshake requires
 * the SockJS protocol path ({@code /ws-chat/{server}/{session}/websocket}) — a
 * raw WebSocket upgrade to {@code /ws-chat} returns HTTP 400. Since
 * {@code StandardWebSocketClient} speaks raw WebSocket (not the SockJS
 * protocol), this test registers a second, test-only NON-SockJS endpoint
 * {@code /ws-chat-test} via {@link TestWebSocketConfig} (a
 * {@code @TestConfiguration} that {@code implements WebSocketMessageBrokerConfigurer}
 * and ADDS the endpoint alongside the prod one). The
 * {@code AuthChannelInterceptor} (CONNECT JWT auth + SUBSCRIBE ownership check)
 * runs on the shared client-inbound channel, so it applies to the test endpoint
 * too — the real-time + ownership guarantees are proven identically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "frame.jwt.secret=test-secret-test-secret-test-secret-test-secret-test-secret",
        "frame.jwt.expire=3600"
})
@Import(ChatIntegrationTest.TestWebSocketConfig.class)
class ChatIntegrationTest {

    /**
     * Registers a raw-WebSocket (non-SockJS) STOMP endpoint for the test client +
     * permits it through Spring Security (the prod {@code SecurityConfig} only
     * permits {@code /ws-chat/**}). Spring merges
     * {@code WebSocketMessageBrokerConfigurer} implementations, so this ADDS
     * {@code /ws-chat-test} alongside the production {@code /ws-chat} (SockJS)
     * endpoint without replacing it. The prod {@code AuthChannelInterceptor}
     * still gates CONNECT + SUBSCRIBE on this endpoint (interceptors are
     * channel-level, not per-endpoint), so JWT auth + ownership are enforced.
     *
     * <p>The extra {@code SecurityFilterChain} bean matches ONLY
     * {@code /ws-chat-test/**} with a higher priority (lower order) than the prod
     * chain and permits it — real STOMP auth happens in the
     * {@code AuthChannelInterceptor} (CONNECT frame), not the HTTP filter. All
     * other paths fall through to the prod chain unchanged.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestWebSocketConfig
            implements org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer {
        @Override
        public void registerStompEndpoints(
                org.springframework.web.socket.config.annotation.StompEndpointRegistry registry) {
            registry.addEndpoint("/ws-chat-test"); // NO .withSockJS() — raw WS for StandardWebSocketClient
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(0) // higher priority than the prod chain (default order)
        org.springframework.security.web.SecurityFilterChain testWsSecurityFilterChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http.securityMatcher("/ws-chat-test/**")
                    .csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserMapper userMapper;

    /** Spring Boot 4.1 dropped TestRestTemplate; RestClient is the modern HTTP
     *  client. create() wires the default Jackson message converters — but we
     *  only read the body as a raw String and parse it ourselves (see below). */
    private final RestClient rest = RestClient.create();
    private StompSession stompSession;

    /**
     * Seed the synthetic chat test users as ACTIVE so the
     * {@link com.iflytek.frame.security.JwtAuthenticationFilter} (which re-checks
     * user status on every authenticated request to revoke soft-deleted accounts)
     * finds non-deleted rows and accepts the JWTs. The JWT userId claims
     * (1001/2001/2002) are not verified against DB ids by the filter — only
     * username + status. Idempotent across the shared DB.
     */
    @BeforeEach
    void seedActiveUsers() {
        seedUser("testuser", "13900001001");
        seedUser("owneruser", "13900002001");
        seedUser("otheruser", "13900002002");
    }

    private void seedUser(String username, String phone) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> qw =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username);
        User existing = userMapper.selectOne(qw);
        if (existing == null) {
            User u = new User();
            u.setUsername(username);
            u.setPasswordHash("$2a$10$placeholderhashforintegrationtestonly");
            u.setRole(Role.USER);
            u.setPhone(phone);
            u.setStatus("ACTIVE");
            u.setFailedAttempts(0);
            userMapper.insert(u);
        } else if (!"ACTIVE".equals(existing.getStatus())) {
            User upd = new User();
            upd.setId(existing.getId());
            upd.setStatus("ACTIVE");
            userMapper.updateById(upd);
        }
    }

    @AfterEach
    void disconnect() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
            stompSession = null;
        }
    }

    /**
     * Core real-time guarantee: a user sends a chat message over STOMP and
     * receives it on the session topic within seconds, and it is also persisted
     * (recoverable via the REST history endpoint).
     */
    @Test
    void userMessage_reachesTopicAndPersists() throws Exception {
        // 1. Mint a USER jwt + open a session via REST POST /chat/sessions
        String userToken = jwtUtil.generateToken(1001L, "testuser", Role.USER);
        Long sessionId = openSessionViaRest(userToken);
        assertNotNull(sessionId, "session id should be returned by POST /chat/sessions");

        // 2. Connect a STOMP client with the JWT in the CONNECT header
        WebSocketStompClient stompClient = newStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + userToken);

        ArrayBlockingQueue<ChatMessageVO> received = new ArrayBlockingQueue<>(1);
        CompletableFuture<StompSession> connectFuture = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws-chat-test",
                new org.springframework.web.socket.WebSocketHttpHeaders(), // no extra handshake headers
                connectHeaders,
                new StompSessionHandlerAdapter() {});
        stompSession = connectFuture.get(10, TimeUnit.SECONDS);
        assertTrue(stompSession.isConnected(), "STOMP session should be connected");

        // 3. Subscribe to /topic/session/{sessionId} — the ownership check in
        //    AuthChannelInterceptor must allow this (the user owns the session).
        //    Overriding getPayloadType is essential: the default returns String,
        //    which would deserialize the JSON body as a raw string (so
        //    `payload instanceof ChatMessageVO` would never hold).
        stompSession.subscribe("/topic/session/" + sessionId, new StompSessionHandlerAdapter() {
            @Override
            public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
                return ChatMessageVO.class;
            }
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof ChatMessageVO msg) {
                    received.add(msg);
                }
            }
        });

        // Give the SUBSCRIBE receipt a moment to register server-side before
        // sending (avoids a race where the SEND arrives before the subscription
        // is active, causing the broker to drop the push).
        Thread.sleep(200);

        // 4. Send a message to /app/chat/{sessionId} (the @MessageMapping destination)
        ChatMessageRequest body = new ChatMessageRequest();
        body.setContent("hello from test");
        body.setImageUrl(null);
        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/app/chat/" + sessionId);
        stompSession.send(sendHeaders, body);

        // 5. Assert the message arrives on the topic (real-time push proof)
        ChatMessageVO msg = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(msg, "message should be pushed to /topic/session/{id} within 5s");
        assertEquals("hello from test", msg.getContent());
        assertEquals("USER", msg.getSenderType());
        assertEquals(1001L, msg.getSenderId());
        assertEquals(sessionId, msg.getSessionId());

        // 6. Assert it persisted — fetch history via REST GET /chat/sessions/{id}/messages
        List<ChatMessageVO> history = fetchHistoryViaRest(userToken, sessionId);
        assertTrue(history.stream().anyMatch(m ->
                "hello from test".equals(m.getContent()) && "USER".equals(m.getSenderType())),
                "sent message should appear in the persisted history");
    }

    /**
     * Task 6 SUBSCRIBE ownership check: a USER who does NOT own a session is
     * rejected when subscribing to that session's topic. The CONNECT itself
     * succeeds (the JWT is valid), but the SUBSCRIBE frame is rejected by
     * {@code AuthChannelInterceptor.handleSubscribe}, which closes the session
     * with an error.
     */
    @Test
    void nonOwner_subscribe_rejected() throws Exception {
        // Owner opens a session
        String ownerToken = jwtUtil.generateToken(2001L, "owneruser", Role.USER);
        Long sessionId = openSessionViaRest(ownerToken);
        assertNotNull(sessionId);

        // A different user (not the owner) connects with their own valid JWT
        String otherToken = jwtUtil.generateToken(2002L, "otheruser", Role.USER);
        WebSocketStompClient stompClient = newStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + otherToken);

        // The SUBSCRIBE is rejected by the interceptor: the StompSessionHandler
        // receives the error via handleTransportError / afterConnect fails. The
        // connect future still resolves, but subscribing to the forbidden topic
        // triggers a ERROR frame that disconnects the session.
        java.util.concurrent.atomic.AtomicReference<Throwable> transportError = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<StompSession> connectFuture = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws-chat-test",
                new org.springframework.web.socket.WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        transportError.set(exception);
                    }
                });
        stompSession = connectFuture.get(10, TimeUnit.SECONDS);
        assertTrue(stompSession.isConnected(), "non-owner CONNECT should succeed (valid JWT)");

        // Subscribe to a session the other user does not own. The interceptor
        // throws MessagingException on the SUBSCRIBE frame; the broker sends an
        // ERROR frame and tears down the session. Give it a moment to propagate.
        stompSession.subscribe("/topic/session/" + sessionId, new StompSessionHandlerAdapter() {});

        // The transport error (MessagingException) should arrive within a few
        // seconds as the ERROR frame disconnects the session.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && transportError.get() == null
                && stompSession.isConnected()) {
            Thread.sleep(50);
        }
        assertTrue(transportError.get() != null || !stompSession.isConnected(),
                "non-owner SUBSCRIBE should be rejected (transport error or session closed)");
    }

    // ---- STOMP + REST helpers ----

    /**
     * Builds a {@link WebSocketStompClient} configured for raw-WS STOMP.
     *
     * <p>The {@link MappingJackson2MessageConverter} uses Jackson 2 internally. Its
     * default {@code ObjectMapper} lacks the {@code JavaTimeModule}, so
     * {@code LocalDateTime} fields (e.g. {@code ChatMessageVO.createTime}) would
     * fail to deserialize — the exception is swallowed by
     * {@code StompSessionHandlerAdapter.handleException} (default no-op), making
     * {@code handleFrame} never fire. {@code findAndRegisterModules()} picks up
     * {@code jackson-datatype-jsr310} from the classpath to fix this.
     */
    private static WebSocketStompClient newStompClient() {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
        return stompClient;
    }

    // ---- REST helpers ----

    /**
     * POST /chat/sessions with the USER Bearer token; unwrap {@code Result.data.id}.
     *
     * <p>{@code Result} is an immutable {@code @Getter} class with a private
     * 4-arg constructor (code/message/data/timestamp) and no default ctor, so
     * Jackson cannot directly deserialize the HTTP body into {@code Result<T>}.
     * Instead we read the body as a JSON tree and pull {@code data.id} out —
     * {@code ChatSessionVO} is a plain {@code @Data} bean, so it deserializes
     * cleanly via {@code treeToValue}.
     */
    private Long openSessionViaRest(String token) {
        String json = rest.post()
                .uri("http://localhost:" + port + "/chat/sessions")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertNotNull(json, "POST /chat/sessions should return a body");
        try {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(json);
            int code = root.path("code").asInt(-1);
            assertEquals(200, code, "POST /chat/sessions should succeed (code=200): " + json);
            ChatSessionVO session = objectMapper.treeToValue(root.get("data"), ChatSessionVO.class);
            assertNotNull(session, "Result.data should hold the ChatSessionVO: " + json);
            return session.getId();
        } catch (tools.jackson.core.JacksonException e) {
            return fail("failed to parse session response: " + json, e);
        }
    }

    /** GET /chat/sessions/{id}/messages → unwrap Result.data.records. */
    private List<ChatMessageVO> fetchHistoryViaRest(String token, Long sessionId) {
        String json = rest.method(HttpMethod.GET)
                .uri("http://localhost:" + port + "/chat/sessions/" + sessionId + "/messages?current=1&size=50")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        assertNotNull(json, "GET /chat/sessions/{id}/messages should return a body");
        try {
            tools.jackson.databind.JsonNode root = objectMapper.readTree(json);
            assertEquals(200, root.path("code").asInt(-1),
                    "GET /chat/sessions/{id}/messages should succeed (code=200): " + json);
            // data is a PageResult → { total, current, size, records: [...] }
            com.iflytek.frame.common.PageResult<ChatMessageVO> page =
                    objectMapper.treeToValue(root.get("data"),
                            new tools.jackson.core.type.TypeReference<com.iflytek.frame.common.PageResult<ChatMessageVO>>() {});
            assertNotNull(page, "Result.data should hold the PageResult: " + json);
            return page.getRecords();
        } catch (tools.jackson.core.JacksonException e) {
            return fail("failed to parse history response: " + json, e);
        }
    }
}
