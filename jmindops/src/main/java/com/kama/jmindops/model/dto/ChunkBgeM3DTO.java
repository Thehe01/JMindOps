package com.kama.jmindops.model.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class ChunkBgeM3DTO {
    private String id;
    private String kbId;
    private String docId;
    private String content;
    private float[] embedding;
    private MetaData metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ChunkBgeM3DTO() {}
    public ChunkBgeM3DTO(String id, String kbId, String docId, String content, float[] embedding, MetaData metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.kbId = kbId;
        this.docId = docId;
        this.content = content;
        this.embedding = embedding;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    public MetaData getMetadata() { return metadata; }
    public void setMetadata(MetaData metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static ChunkBgeM3DTOBuilder builder() { return new ChunkBgeM3DTOBuilder(); }
    public static class ChunkBgeM3DTOBuilder {
        private String id;
        private String kbId;
        private String docId;
        private String content;
        private float[] embedding;
        private MetaData metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public ChunkBgeM3DTOBuilder() {}
        public ChunkBgeM3DTOBuilder id(String id) { this.id = id; return this; }
        public ChunkBgeM3DTOBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public ChunkBgeM3DTOBuilder docId(String docId) { this.docId = docId; return this; }
        public ChunkBgeM3DTOBuilder content(String content) { this.content = content; return this; }
        public ChunkBgeM3DTOBuilder embedding(float[] embedding) { this.embedding = embedding; return this; }
        public ChunkBgeM3DTOBuilder metadata(MetaData metadata) { this.metadata = metadata; return this; }
        public ChunkBgeM3DTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ChunkBgeM3DTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ChunkBgeM3DTO build() { return new ChunkBgeM3DTO(id, kbId, docId, content, embedding, metadata, createdAt, updatedAt); }
    }

    @Data
    public static class MetaData {
    }
}
