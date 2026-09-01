package com.kama.jmindops.model.vo;

import com.kama.jmindops.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatMessageVO {
    private String id;
    private String sessionId;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;

    public ChatMessageVO() {}
    public ChatMessageVO(String id, String sessionId, ChatMessageDTO.RoleType role, String content, ChatMessageDTO.MetaData metadata) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.metadata = metadata;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return this.sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public ChatMessageDTO.RoleType getRole() { return this.role; }
    public void setRole(ChatMessageDTO.RoleType role) { this.role = role; }
    public String getContent() { return this.content; }
    public void setContent(String content) { this.content = content; }
    public ChatMessageDTO.MetaData getMetadata() { return this.metadata; }
    public void setMetadata(ChatMessageDTO.MetaData metadata) { this.metadata = metadata; }
    public static ChatMessageVOBuilder builder() { return new ChatMessageVOBuilder(); }
    public static class ChatMessageVOBuilder {
        private String id;
        private String sessionId;
        private ChatMessageDTO.RoleType role;
        private String content;
        private ChatMessageDTO.MetaData metadata;
        public ChatMessageVOBuilder() {}
        public ChatMessageVOBuilder id(String id) { this.id = id; return this; }
        public ChatMessageVOBuilder sessionId(String sessionId) { this.sessionId = sessionId; return this; }
        public ChatMessageVOBuilder role(ChatMessageDTO.RoleType role) { this.role = role; return this; }
        public ChatMessageVOBuilder content(String content) { this.content = content; return this; }
        public ChatMessageVOBuilder metadata(ChatMessageDTO.MetaData metadata) { this.metadata = metadata; return this; }
        public ChatMessageVO build() { return new ChatMessageVO(id, sessionId, role, content, metadata); }
    }
}
