package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.service.ChatService;
import com.iflytek.frame.util.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin", description = "Admin chat inbox + session history")
@RestController
@RequestMapping("/admin/chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final ChatService chatService;

    @Operation(summary = "Admin inbox: open chat sessions")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> inbox() {
        return Result.success(chatService.openSessions());
    }

    @Operation(summary = "Admin reads a session's message history (paginated)")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/sessions/{id}/messages")
    public Result<?> history(@PathVariable Long id,
                             @Valid @ParameterObject PageQuery query) {
        return Result.success(chatService.history(id, query));
    }
}
