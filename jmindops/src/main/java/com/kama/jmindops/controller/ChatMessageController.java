package com.kama.jmindops.controller;

import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.model.request.UpdateChatMessageRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.response.GetChatMessagesResponse;
import com.kama.jmindops.service.ChatMessageFacadeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatMessageController {

    private final ChatMessageFacadeService chatMessageFacadeService;
    public ChatMessageController(ChatMessageFacadeService chatMessageFacadeService) {
        this.chatMessageFacadeService = chatMessageFacadeService;
    }


    // 根据 sessionId 查询聊天消息
    @GetMapping("/chat-messages/session/{sessionId}")
    public ApiResponse<GetChatMessagesResponse> getChatMessagesBySessionId(@PathVariable String sessionId) {
        return ApiResponse.success(chatMessageFacadeService.getChatMessagesBySessionId(sessionId));
    }

    // 创建聊天消息
    @PostMapping("/chat-messages")
    public ApiResponse<CreateChatMessageResponse> createChatMessage(@Valid @RequestBody CreateChatMessageRequest request) {
        return ApiResponse.success(chatMessageFacadeService.createChatMessage(request));
    }

    // 删除聊天消息
    @DeleteMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> deleteChatMessage(@PathVariable String chatMessageId) {
        chatMessageFacadeService.deleteChatMessage(chatMessageId);
        return ApiResponse.success();
    }

    // 更新聊天消息
    @PatchMapping("/chat-messages/{chatMessageId}")
    public ApiResponse<Void> updateChatMessage(@PathVariable String chatMessageId, @Valid @RequestBody UpdateChatMessageRequest request) {
        chatMessageFacadeService.updateChatMessage(chatMessageId, request);
        return ApiResponse.success();
    }
}
