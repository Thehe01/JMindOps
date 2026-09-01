package com.kama.jmindops.model.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionDTO {
    private String id;
    private String agentId;
    private String title;
    private MetaData metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ChatSessionDTO() {}
    public ChatSessionDTO(String id, String agentId, String title, MetaData metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.agentId = agentId;
        this.title = title;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return this.title; }
    public void setTitle(String title) { this.title = title; }
    public MetaData getMetadata() { return this.metadata; }
    public void setMetadata(MetaData metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static ChatSessionDTOBuilder builder() { return new ChatSessionDTOBuilder(); }
    public static class ChatSessionDTOBuilder {
        private String id;
        private String agentId;
        private String title;
        private MetaData metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public ChatSessionDTOBuilder() {}
        public ChatSessionDTOBuilder id(String id) { this.id = id; return this; }
        public ChatSessionDTOBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public ChatSessionDTOBuilder title(String title) { this.title = title; return this; }
        public ChatSessionDTOBuilder metadata(MetaData metadata) { this.metadata = metadata; return this; }
        public ChatSessionDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ChatSessionDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ChatSessionDTO build() { return new ChatSessionDTO(id, agentId, title, metadata, createdAt, updatedAt); }
    }

    @Data
    public static class MetaData {
    }
}
