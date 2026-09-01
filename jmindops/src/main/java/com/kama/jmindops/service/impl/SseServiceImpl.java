package com.kama.jmindops.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.service.SseService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Set;

@Service
public class SseServiceImpl implements SseService {
    private static final Logger log = LoggerFactory.getLogger(SseServiceImpl.class);


    private static final int MAX_CONNECTIONS_PER_SESSION = 5;
    private final ConcurrentMap<String, Set<SseEmitter>> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    public SseServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.compute(chatSessionId, (ignored, existing) -> {
            Set<SseEmitter> emitters = existing == null ? ConcurrentHashMap.newKeySet() : existing;
            if (emitters.size() >= MAX_CONNECTIONS_PER_SESSION) {
                throw new BizException(429, "该会话的实时连接数已达上限");
            }
            emitters.add(emitter);
            return emitters;
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        emitter.onCompletion(() -> removeClient(chatSessionId, emitter));
        emitter.onTimeout(() -> removeClient(chatSessionId, emitter));
        emitter.onError((error) -> removeClient(chatSessionId, emitter));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        Set<SseEmitter> emitters = clients.get(chatSessionId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE client for chatSessionId={}, skipping push", chatSessionId);
            return;
        }

        try {
            String sseMessageStr = objectMapper.writeValueAsString(message);
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(sseMessageStr));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Removing unavailable SSE client for chatSessionId={}", chatSessionId, e);
                    removeClient(chatSessionId, emitter);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize SSE message", e);
        }
    }

    private void removeClient(String chatSessionId, SseEmitter emitter) {
        clients.computeIfPresent(chatSessionId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
