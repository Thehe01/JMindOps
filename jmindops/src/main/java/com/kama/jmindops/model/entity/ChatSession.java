package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName chat_session
 */
@Data
@Builder
public class ChatSession {
    private String id;

    private String ownerId;

    private String agentId;

    private String title;

    // JSON string
    private String metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    
    public ChatSession() {}
    public ChatSession(String id, String ownerId, String agentId, String title, String metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.agentId = agentId;
        this.title = title;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return this.ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getAgentId() { return this.agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return this.title; }
    public void setTitle(String title) { this.title = title; }
    public String getMetadata() { return this.metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public static ChatSessionBuilder builder() { return new ChatSessionBuilder(); }
    public static class ChatSessionBuilder {
        private String id;
        private String ownerId;
        private String agentId;
        private String title;
        private String metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public ChatSessionBuilder() {}
        public ChatSessionBuilder id(String id) { this.id = id; return this; }
        public ChatSessionBuilder ownerId(String ownerId) { this.ownerId = ownerId; return this; }
        public ChatSessionBuilder agentId(String agentId) { this.agentId = agentId; return this; }
        public ChatSessionBuilder title(String title) { this.title = title; return this; }
        public ChatSessionBuilder metadata(String metadata) { this.metadata = metadata; return this; }
        public ChatSessionBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ChatSessionBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ChatSession build() { return new ChatSession(id, ownerId, agentId, title, metadata, createdAt, updatedAt); }
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        ChatSession other = (ChatSession) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getAgentId() == null ? other.getAgentId() == null : this.getAgentId().equals(other.getAgentId()))
            && (this.getTitle() == null ? other.getTitle() == null : this.getTitle().equals(other.getTitle()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getAgentId() == null) ? 0 : getAgentId().hashCode());
        result = prime * result + ((getTitle() == null) ? 0 : getTitle().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", agentId=" + agentId +
                ", title=" + title +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
