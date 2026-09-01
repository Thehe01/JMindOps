package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName knowledge_base
 */
@Data
@Builder
public class KnowledgeBase {
    private String id;

    private String ownerId;

    private String name;

    private String description;

    private String metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    
    public KnowledgeBase() {}
    public KnowledgeBase(String id, String ownerId, String name, String description, String metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return this.ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return this.description; }
    public void setDescription(String description) { this.description = description; }
    public String getMetadata() { return this.metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public static KnowledgeBaseBuilder builder() { return new KnowledgeBaseBuilder(); }
    public static class KnowledgeBaseBuilder {
        private String id;
        private String ownerId;
        private String name;
        private String description;
        private String metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public KnowledgeBaseBuilder() {}
        public KnowledgeBaseBuilder id(String id) { this.id = id; return this; }
        public KnowledgeBaseBuilder ownerId(String ownerId) { this.ownerId = ownerId; return this; }
        public KnowledgeBaseBuilder name(String name) { this.name = name; return this; }
        public KnowledgeBaseBuilder description(String description) { this.description = description; return this; }
        public KnowledgeBaseBuilder metadata(String metadata) { this.metadata = metadata; return this; }
        public KnowledgeBaseBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public KnowledgeBaseBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public KnowledgeBase build() { return new KnowledgeBase(id, ownerId, name, description, metadata, createdAt, updatedAt); }
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
        KnowledgeBase other = (KnowledgeBase) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getName() == null ? other.getName() == null : this.getName().equals(other.getName()))
            && (this.getDescription() == null ? other.getDescription() == null : this.getDescription().equals(other.getDescription()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getName() == null) ? 0 : getName().hashCode());
        result = prime * result + ((getDescription() == null) ? 0 : getDescription().hashCode());
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
                ", name=" + name +
                ", description=" + description +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
