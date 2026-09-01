package com.kama.jmindops.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatSessionVO {
    private String id;
    private String agentId;
    private String title;

    public ChatSessionVO() {}
    public ChatSessionVO(String id, String agentId, String title) {
        this.id = id;
        this.agentId = agentId;
        this.title = title;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return this.title; }
    public void setTitle(String title) { this.title = title; }
    public static ChatSessionVOBuilder builder() { return new ChatSessionVOBuilder(); }
    public static class ChatSessionVOBuilder {
        private String id;
        private String agentId;
        private String title;
        public ChatSessionVOBuilder() {}
        public ChatSessionVOBuilder id(String id) { this.id = id; return this; }
        public ChatSessionVOBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public ChatSessionVOBuilder title(String title) { this.title = title; return this; }
        public ChatSessionVO build() { return new ChatSessionVO(id, agentId, title); }
    }
}
