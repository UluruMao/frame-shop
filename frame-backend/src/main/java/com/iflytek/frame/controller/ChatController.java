package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.service.ChatService;
import com.iflytek.frame.util.PageQuery;
import com.iflytek.frame.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Chat", description = "User-facing customer-service chat")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "Open (or reuse) the current user's chat session")
    @PostMapping("/sessions")
    public Result<ChatSessionVO> openSession() {
        return Result.success(chatService.findOrCreateOpenSession(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Fetch the current user's chat-session message history (paginated, owner-only)")
    @GetMapping("/sessions/{id}/messages")
    public Result<?> history(@PathVariable Long id,
                             @Valid @ParameterObject PageQuery query) {
        // ownership checked in the service layer (historyForUser) before paginating
        return Result.success(chatService.historyForUser(id, SecurityUtils.currentUserId(), query));
    }
}
