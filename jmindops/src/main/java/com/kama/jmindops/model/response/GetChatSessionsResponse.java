package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.ChatSessionVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class GetChatSessionsResponse {
    private ChatSessionVO[] chatSessions;

    public GetChatSessionsResponse() {}
    public GetChatSessionsResponse(ChatSessionVO[] chatSessions) {
        this.chatSessions = chatSessions;
    }

    public ChatSessionVO[] getChatSessions() { return chatSessions; }
    public void setChatSessions(ChatSessionVO[] chatSessions) { this.chatSessions = chatSessions; }

    public static GetChatSessionsResponseBuilder builder() { return new GetChatSessionsResponseBuilder(); }
    public static class GetChatSessionsResponseBuilder {
        private ChatSessionVO[] chatSessions;
        public GetChatSessionsResponseBuilder() {}
        public GetChatSessionsResponseBuilder chatSessions(ChatSessionVO[] chatSessions) { this.chatSessions = chatSessions; return this; }
        public GetChatSessionsResponse build() { return new GetChatSessionsResponse(chatSessions); }
    }
}
