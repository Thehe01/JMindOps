package com.kama.jmindops.controller;

import com.kama.jmindops.service.SseService;
import com.kama.jmindops.service.ChatSessionFacadeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/sse")
public class SseController {

    private final SseService sseService;
    private final ChatSessionFacadeService chatSessionFacadeService;
    public SseController(SseService sseService, ChatSessionFacadeService chatSessionFacadeService) {
        this.sseService = sseService;
        this.chatSessionFacadeService = chatSessionFacadeService;
    }


    // 处理 sse 连接
    @RequestMapping(value = "/connect/{chatSessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String chatSessionId) {
        chatSessionFacadeService.getChatSession(chatSessionId);
        return sseService.connect(chatSessionId);
    }
}
