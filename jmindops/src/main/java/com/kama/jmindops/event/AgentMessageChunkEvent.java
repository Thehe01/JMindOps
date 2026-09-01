package com.kama.jmindops.event;

import org.springframework.context.ApplicationEvent;

public class AgentMessageChunkEvent extends ApplicationEvent {

    private final String chatSessionId;
    private final String generationId;
    private final String chunkText;

    public AgentMessageChunkEvent(Object source, String chatSessionId, String chunkText) {
        this(source, chatSessionId, null, chunkText);
    }

    public AgentMessageChunkEvent(Object source, String chatSessionId, String generationId, String chunkText) {
        super(source);
        this.chatSessionId = chatSessionId;
        this.generationId = generationId;
        this.chunkText = chunkText;
    }

    public String getChatSessionId() {
        return chatSessionId;
    }

    public String getChunkText() {
        return chunkText;
    }

    public String getGenerationId() {
        return generationId;
    }
}
