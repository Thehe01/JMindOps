package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatMessageResponse {
    private String chatMessageId;
    private String generationId;

    public CreateChatMessageResponse() {}
    public CreateChatMessageResponse(String chatMessageId, String generationId) {
        this.chatMessageId = chatMessageId;
        this.generationId = generationId;
    }
    public String getChatMessageId() { return this.chatMessageId; }
    public void setChatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; }
    public String getGenerationId() { return this.generationId; }
    public void setGenerationId(String generationId) { this.generationId = generationId; }
    public static CreateChatMessageResponseBuilder builder() { return new CreateChatMessageResponseBuilder(); }
    public static class CreateChatMessageResponseBuilder {
        private String chatMessageId;
        private String generationId;
        public CreateChatMessageResponseBuilder() {}
        public CreateChatMessageResponseBuilder chatMessageId(String chatMessageId) { this.chatMessageId = chatMessageId; return this; }
        public CreateChatMessageResponseBuilder generationId(String generationId) { this.generationId = generationId; return this; }
        public CreateChatMessageResponse build() { return new CreateChatMessageResponse(chatMessageId, generationId); }
    }
}

