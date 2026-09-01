package com.kama.jmindops.model.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseDTO {
    private String id;
    private String name;
    private String description;
    private MetaData metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public KnowledgeBaseDTO() {}
    public KnowledgeBaseDTO(String id, String name, String description, MetaData metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return this.description; }
    public void setDescription(String description) { this.description = description; }
    public MetaData getMetadata() { return this.metadata; }
    public void setMetadata(MetaData metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static KnowledgeBaseDTOBuilder builder() { return new KnowledgeBaseDTOBuilder(); }
    public static class KnowledgeBaseDTOBuilder {
        private String id;
        private String name;
        private String description;
        private MetaData metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public KnowledgeBaseDTOBuilder() {}
        public KnowledgeBaseDTOBuilder id(String id) { this.id = id; return this; }
        public KnowledgeBaseDTOBuilder name(String name) { this.name = name; return this; }
        public KnowledgeBaseDTOBuilder description(String description) { this.description = description; return this; }
        public KnowledgeBaseDTOBuilder metadata(MetaData metadata) { this.metadata = metadata; return this; }
        public KnowledgeBaseDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public KnowledgeBaseDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public KnowledgeBaseDTO build() { return new KnowledgeBaseDTO(id, name, description, metadata, createdAt, updatedAt); }
    }

    @Data
    @Builder
    public static class MetaData {
        private String version;

        public MetaData() {}
        public MetaData(String version) { this.version = version; }
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
    }

    @Override
    public String toString() {
        return "{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
