package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.security.SecurityUser;
import com.iflytek.frame.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/sessions")
    public Result<ChatSessionVO> openSession(Principal principal) {
        Long userId = currentUserId(principal);
        return Result.success(chatService.findOrCreateOpenSession(userId));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<?> history(@PathVariable Long id,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size,
                             Principal principal) {
        // ownership checked in the service layer for user-facing reads
        return Result.success(chatService.history(id, page, size));
    }

    private Long currentUserId(Principal principal) {
        SecurityUser su = (SecurityUser) principal;
        return su.userId();
    }
}
