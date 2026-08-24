package com.iflytek.frame.controller;

import com.iflytek.frame.common.Result;
import com.iflytek.frame.dto.ChatSessionVO;
import com.iflytek.frame.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/chat")
@RequiredArgsConstructor
public class AdminChatController {

    private final ChatService chatService;

    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> inbox() {
        return Result.success(chatService.openSessions());
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<?> history(@PathVariable Long id,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return Result.success(chatService.history(id, page, size));
    }
}
