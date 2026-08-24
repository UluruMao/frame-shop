package com.iflytek.frame.websocket;

import com.iflytek.frame.dto.ChatMessageRequest;
import com.iflytek.frame.dto.ChatMessageVO;
import com.iflytek.frame.security.SecurityUser;
import com.iflytek.frame.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class ChatStompController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat/{sessionId}")
    public void handleChatMessage(@DestinationVariable Long sessionId,
                                  @Payload ChatMessageRequest req,
                                  Principal principal) {
        SecurityUser su = (SecurityUser) principal;
        ChatMessageVO msg = switch (su.role().name()) {
            case "ADMIN" -> chatService.sendAdminMessage(sessionId, su.userId(), req.getContent(), req.getImageUrl());
            default -> chatService.sendUserMessage(sessionId, su.userId(), req.getContent(), req.getImageUrl());
        };
        // persist-before-publish (already persisted in the service); now push to the session topic
        messagingTemplate.convertAndSend("/topic/session/" + sessionId, msg);
    }
}
