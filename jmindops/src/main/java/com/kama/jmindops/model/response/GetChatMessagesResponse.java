package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.ChatMessageVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class GetChatMessagesResponse {
    private ChatMessageVO[] chatMessages;

    public GetChatMessagesResponse() {}
    public GetChatMessagesResponse(ChatMessageVO[] chatMessages) {
        this.chatMessages = chatMessages;
    }

    public ChatMessageVO[] getChatMessages() { return chatMessages; }
    public void setChatMessages(ChatMessageVO[] chatMessages) { this.chatMessages = chatMessages; }

    public static GetChatMessagesResponseBuilder builder() { return new GetChatMessagesResponseBuilder(); }
    public static class GetChatMessagesResponseBuilder {
        private ChatMessageVO[] chatMessages;
        public GetChatMessagesResponseBuilder() {}
        public GetChatMessagesResponseBuilder chatMessages(ChatMessageVO[] chatMessages) { this.chatMessages = chatMessages; return this; }
        public GetChatMessagesResponse build() { return new GetChatMessagesResponse(chatMessages); }
    }
}
