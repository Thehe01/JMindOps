package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateChatSessionResponse {
    private String chatSessionId;

    public CreateChatSessionResponse() {}
    public CreateChatSessionResponse(String chatSessionId) {
        this.chatSessionId = chatSessionId;
    }
    public String getChatSessionId() { return this.chatSessionId; }
    public void setChatSessionId(String chatSessionId) { this.chatSessionId = chatSessionId; }
    public static CreateChatSessionResponseBuilder builder() { return new CreateChatSessionResponseBuilder(); }
    public static class CreateChatSessionResponseBuilder {
        private String chatSessionId;
        public CreateChatSessionResponseBuilder() {}
        public CreateChatSessionResponseBuilder chatSessionId(String chatSessionId) { this.chatSessionId = chatSessionId; return this; }
        public CreateChatSessionResponse build() { return new CreateChatSessionResponse(chatSessionId); }
    }
}
