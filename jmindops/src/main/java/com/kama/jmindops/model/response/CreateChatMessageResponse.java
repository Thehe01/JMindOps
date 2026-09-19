package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageResponse {
    private String chatMessageId;
    private String generationId;
    private String requestId;
    private String status;
    private boolean idempotentReplay;

    public CreateChatMessageResponse() {}
    public CreateChatMessageResponse(String chatMessageId, String generationId, String requestId, String status, boolean idempotentReplay) {
        this.chatMessageId = chatMessageId;
        this.generationId = generationId;
        this.requestId = requestId;
        this.status = status;
        this.idempotentReplay = idempotentReplay;
    }
    public String getChatMessageId() { return this.chatMessageId; }
    public void setChatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; }
    public String getGenerationId() { return this.generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public String getRequestId() { return this.requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isIdempotentReplay() { return this.idempotentReplay; }
    public void setIdempotentReplay(boolean idempotentReplay) { this.idempotentReplay = idempotentReplay; }
    public static CreateChatMessageResponseBuilder builder() { return new CreateChatMessageResponseBuilder(); }
    public static class CreateChatMessageResponseBuilder {
        private String chatMessageId;
        private String generationId;
        private String requestId;
        private String status;
        private boolean idempotentReplay;
        public CreateChatMessageResponseBuilder() {}
        public CreateChatMessageResponseBuilder chatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; return this; }
        public CreateChatMessageResponseBuilder generationId(String generationId) { this.generationId = generationId; return this; }
        public CreateChatMessageResponseBuilder requestId(String requestId) { this.requestId = requestId; return this; }
        public CreateChatMessageResponseBuilder status(String status) { this.status = status; return this; }
        public CreateChatMessageResponseBuilder idempotentReplay(boolean idempotentReplay) { this.idempotentReplay = idempotentReplay; return this; }
        public CreateChatMessageResponse build() { return new CreateChatMessageResponse(chatMessageId, generationId, requestId, status, idempotentReplay); }
    }
}

