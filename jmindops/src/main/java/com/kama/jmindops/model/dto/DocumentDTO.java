package com.kama.jmindops.model.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class DocumentDTO {
    private String id;
    private String kbId;
    private String filename;
    private String filetype;
    private Long size;
    private MetaData metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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
    public MetaData getMetadata() { return this.metadata; }
    public void setMetadata(MetaData metadata) { this.metadata = metadata; }
    public LocalDateTime getCreatedAt() { return this.createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return this.updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static DocumentDTOBuilder builder() { return new DocumentDTOBuilder(); }
    public static class DocumentDTOBuilder {
        private String id;
        private String kbId;
        private String filename;
        private String filetype;
        private Long size;
        private MetaData metadata;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public DocumentDTOBuilder() {}
        public DocumentDTOBuilder id(String id) { this.id = id; return this; }
        public DocumentDTOBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public DocumentDTOBuilder filename(String filename) { this.filename = filename; return this; }
        public DocumentDTOBuilder filetype(String filetype) { this.filetype = filetype; return this; }
        public DocumentDTOBuilder size(Long size) { this.size = size; return this; }
        public DocumentDTOBuilder metadata(MetaData metadata) { this.metadata = metadata; return this; }
        public DocumentDTOBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public DocumentDTOBuilder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }
        public DocumentDTO build() { return new DocumentDTO(id, kbId, filename, filetype, size, metadata, createdAt, updatedAt); }
    }

    @Data
    @Builder
    public static class MetaData {
        private String filePath;

        public MetaData() {}
        public MetaData(String filePath) { this.filePath = filePath; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
    }
}
