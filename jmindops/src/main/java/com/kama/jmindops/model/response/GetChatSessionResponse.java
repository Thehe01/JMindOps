package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.ChatSessionVO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GetChatSessionResponse {
    private ChatSessionVO chatSession;

    public GetChatSessionResponse() {}
    public GetChatSessionResponse(ChatSessionVO chatSession) {
        this.chatSession = chatSession;
    }
    public ChatSessionVO getChatSession() { return this.chatSession; }
    public void setChatSession(ChatSessionVO chatSession) { this.chatSession = chatSession; }
    public static GetChatSessionResponseBuilder builder() { return new GetChatSessionResponseBuilder(); }
    public static class GetChatSessionResponseBuilder {
        private ChatSessionVO chatSession;
        public GetChatSessionResponseBuilder() {}
        public GetChatSessionResponseBuilder chatSession(ChatSessionVO chatSession) { this.chatSession = chatSession; return this; }
        public GetChatSessionResponse build() { return new GetChatSessionResponse(chatSession); }
    }
}
