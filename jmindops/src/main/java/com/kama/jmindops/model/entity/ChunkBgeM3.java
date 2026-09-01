package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.Builder;
import lombok.Data;
/**
 * @TableName chunk_bge_m3
 */
@Data
@Builder
public class ChunkBgeM3 {
    private String id;
    private String kbId;
    private String docId;
    private String content;
    private float[] embedding;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ChunkBgeM3() {}
    public ChunkBgeM3(String id, String kbId, String docId, String content, float[] embedding, String metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
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
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static ChunkBgeM3Builder builder() { return new ChunkBgeM3Builder(); }
    public static class ChunkBgeM3Builder {
        private String id;
        private String kbId;
        private String docId;
        private String content;
        private float[] embedding;
        private String metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public ChunkBgeM3Builder() {}
        public ChunkBgeM3Builder id(String id) { this.id = id; return this; }
        public ChunkBgeM3Builder kbId(String kbId) { this.kbId = kbId; return this; }
        public ChunkBgeM3Builder docId(String docId) { this.docId = docId; return this; }
        public ChunkBgeM3Builder content(String content) { this.content = content; return this; }
        public ChunkBgeM3Builder embedding(float[] embedding) { this.embedding = embedding; return this; }
        public ChunkBgeM3Builder metadata(String metadata) { this.metadata = metadata; return this; }
        public ChunkBgeM3Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public ChunkBgeM3Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public ChunkBgeM3 build() { return new ChunkBgeM3(id, kbId, docId, content, embedding, metadata, createdAt, updatedAt); }
    }

    @Override
    public boolean equals(Object that) {
        if (this == that) return true;
        if (that == null || getClass() != that.getClass()) return false;
        ChunkBgeM3 other = (ChunkBgeM3) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
            && (this.getDocId() == null ? other.getDocId() == null : this.getDocId().equals(other.getDocId()))
            && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
            && (Arrays.equals(this.getEmbedding(), other.getEmbedding()))
            && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
            && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
            && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getKbId() == null) ? 0 : getKbId().hashCode());
        result = prime * result + ((getDocId() == null) ? 0 : getDocId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + Arrays.hashCode(getEmbedding());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }
}
