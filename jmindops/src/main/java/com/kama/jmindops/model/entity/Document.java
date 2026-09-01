package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Data;

/**
 * @TableName document
 */
@Data
@Builder
public class Document {
    private String id;

    private String kbId;

    private String filename;

    private String filetype;

    private Long size;

    // JSON String
    private String metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    
    public Document() {}
    public Document(String id, String kbId, String filename, String filetype, Long size, String metadata, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.kbId = kbId;
        this.filename = filename;
        this.filetype = filetype;
        this.size = size;
        this.metadata = metadata;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    public String getId() { return this.id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return this.kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getFilename() { return this.filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getFiletype() { return this.filetype; }
    public void setFiletype(String filetype) { this.filetype = filetype; }
    public Long getSize() { return this.size; }
    public void setSize(Long size) { this.size = size; }
    public String getMetadata() { return this.metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public static DocumentBuilder builder() { return new DocumentBuilder(); }
    public static class DocumentBuilder {
        private String id;
        private String kbId;
        private String filename;
        private String filetype;
        private Long size;
        private String metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        public DocumentBuilder() {}
        public DocumentBuilder id(String id) { this.id = id; return this; }
        public DocumentBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public DocumentBuilder filename(String filename) { this.filename = filename; return this; }
        public DocumentBuilder filetype(String filetype) { this.filetype = filetype; return this; }
        public DocumentBuilder size(Long size) { this.size = size; return this; }
        public DocumentBuilder metadata(String metadata) { this.metadata = metadata; return this; }
        public DocumentBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public DocumentBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public Document build() { return new Document(id, kbId, filename, filetype, size, metadata, createdAt, updatedAt); }
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
        Document other = (Document) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
            && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
            && (this.getFilename() == null ? other.getFilename() == null : this.getFilename().equals(other.getFilename()))
            && (this.getFiletype() == null ? other.getFiletype() == null : this.getFiletype().equals(other.getFiletype()))
            && (this.getSize() == null ? other.getSize() == null : this.getSize().equals(other.getSize()))
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
        result = prime * result + ((getFilename() == null) ? 0 : getFilename().hashCode());
        result = prime * result + ((getFiletype() == null) ? 0 : getFiletype().hashCode());
        result = prime * result + ((getSize() == null) ? 0 : getSize().hashCode());
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
                ", kbId=" + kbId +
                ", filename=" + filename +
                ", filetype=" + filetype +
                ", size=" + size +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}