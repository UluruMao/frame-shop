package com.iflytek.frame.config;

import com.iflytek.frame.dto.ChatMessageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Task 3 — Redis pub/sub chat broker relay, unit test of the re-broadcast logic.
 *
 * <p>The relay ({@link RedisChatBrokerRelay}) is a Spring Data Redis
 * {@code MessageListener} subscribed to the pattern {@code chat:session:*}.
 * When a chat message is published to {@code chat:session:{sessionId}} on any
 * instance, every instance's relay receives it and re-broadcasts the payload to
 * its own local WS subscribers via
 * {@code simpMessagingTemplate.convertAndSend("/topic/session/{sessionId}", payload)}.
 *
 * <p>This test isolates that mapping: feed the relay a Redis {@link Message}
 * whose channel is {@code chat:session:7} and whose body is a serialized
 * {@link ChatMessageVO}, then verify the local
 * {@link SimpMessagingTemplate} re-broadcasts the VO to
 * {@code /topic/session/7}. This is the cross-instance delivery guarantee —
 * the unit the brief calls out in Step 1.
 */
@ExtendWith(MockitoExtension.class)
class RedisChatBrokerTest {

    /** The relay under test — constructed by hand (no Spring context) so the
     *  test stays a focused unit test of the onMessage→convertAndSend mapping. */
    private RedisChatBrokerRelay relay;

    @Mock
    SimpMessagingTemplate simpMessagingTemplate;

    @BeforeEach
    void setUp() {
        // The relay resolves SimpMessagingTemplate lazily via ObjectProvider (to
        // break a bean-creation cycle in production). For this unit test, a mock
        // provider returning the mock template mirrors that.
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<SimpMessagingTemplate> provider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(simpMessagingTemplate);
        relay = new RedisChatBrokerRelay(
                provider,
                new tools.jackson.databind.ObjectMapper());
    }

    @Test
    void onMessage_chatSessionChannel_rebroadcastsToTopicSession() {
        // A ChatMessageVO as it would be serialized to the Redis channel body.
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(42L);
        vo.setSessionId(7L);
        vo.setSenderType("USER");
        vo.setSenderId(1001L);
        vo.setContent("hello across instances");
        vo.setImageUrl(null);
        String bodyJson = """
                {"id":42,"sessionId":7,"senderType":"USER","senderId":1001,"content":"hello across instances","imageUrl":null}""";

        Message redisMessage = new TestRedisMessage(
                "chat:session:7".getBytes(StandardCharsets.UTF_8),
                bodyJson.getBytes(StandardCharsets.UTF_8));

        relay.onMessage(redisMessage, null);

        // The relay re-broadcasts the deserialized VO to the local WS topic —
        // this is the cross-instance fan-in that lets instance B's subscribers
        // receive a message published on instance A.
        verify(simpMessagingTemplate).convertAndSend(
                eq("/topic/session/7"), eq(vo));
    }

    // ---- minimal Redis Message stubs ----

    /** A trivial Redis {@link Message} for testing: holds the raw channel +
     *  body bytes and returns them. Redis pub/sub messages are just byte arrays. */
    private static final class TestRedisMessage implements Message {
        private final byte[] channel;
        private final byte[] body;

        TestRedisMessage(byte[] channel, byte[] body) {
            this.channel = channel;
            this.body = body;
        }

        @Override
        public byte[] getChannel() {
            return channel;
        }

        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "TestRedisMessage{channel=" + new String(channel, StandardCharsets.UTF_8) + "}";
        }
    }
}
