package com.kama.jmindops.event;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.context.ApplicationEvent;

public class AgentMessageGeneratedEvent extends ApplicationEvent {
    
    private final String chatSessionId;
    private final String generationId;
    private final Message generatedMessage;
    private final Usage usage;
    private final Long latencyMs;
    private final String model;

    public AgentMessageGeneratedEvent(Object source, String chatSessionId, Message generatedMessage) {
        this(source, chatSessionId, null, generatedMessage, null, null, null);
    }

    public AgentMessageGeneratedEvent(Object source, String chatSessionId, Message generatedMessage, Usage usage, Long latencyMs, String model) {
        this(source, chatSessionId, null, generatedMessage, usage, latencyMs, model);
    }

    public AgentMessageGeneratedEvent(
            Object source,
            String chatSessionId,
            String generationId,
            Message generatedMessage,
            Usage usage,
            Long latencyMs,
            String model
    ) {
        super(source);
        this.chatSessionId = chatSessionId;
        this.generationId = generationId;
        this.generatedMessage = generatedMessage;
        this.usage = usage;
        this.latencyMs = latencyMs;
        this.model = model;
    }

    public String getChatSessionId() {
        return chatSessionId;
    }

    public Message getGeneratedMessage() {
        return generatedMessage;
    }

    public String getGenerationId() {
        return generationId;
    }

    public Usage getUsage() {
        return usage;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public String getModel() {
        return model;
    }
}
