package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.service.ChatService;
import com.iflytek.frame.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions")
    public Result<ChatSessionVO> openSession() {
        return Result.success(chatService.findOrCreateOpenSession(SecurityUtils.currentUserId()));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<?> history(@PathVariable Long id,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        // ownership checked in the service layer (historyForUser) before paginating
        return Result.success(chatService.historyForUser(id, SecurityUtils.currentUserId(), page, size));
    }
}
