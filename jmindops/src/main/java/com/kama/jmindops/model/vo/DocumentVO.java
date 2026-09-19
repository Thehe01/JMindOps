package com.kama.jmindops.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentVO {
    private String id;
    private String kbId;
    private String filename;
    private String filetype;
    private Long size;
    private String indexStatus;
    private Integer indexVersion;
    private Integer chunkCount;

    public DocumentVO() {}
    public DocumentVO(String id, String kbId, String filename, String filetype, Long size, String indexStatus, Integer indexVersion, Integer chunkCount) {
        this.id = id;
        this.kbId = kbId;
        this.filename = filename;
        this.filetype = filetype;
        this.size = size;
        this.indexStatus = indexStatus;
        this.indexVersion = indexVersion;
        this.chunkCount = chunkCount;
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
    public String getIndexStatus() { return indexStatus; }
    public void setIndexStatus(String indexStatus) { this.indexStatus = indexStatus; }
    public Integer getIndexVersion() { return indexVersion; }
    public void setIndexVersion(Integer indexVersion) { this.indexVersion = indexVersion; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public static DocumentVOBuilder builder() { return new DocumentVOBuilder(); }
    public static class DocumentVOBuilder {
        private String id;
        private String kbId;
        private String filename;
        private String filetype;
        private Long size;
        private String indexStatus;
        private Integer indexVersion;
        private Integer chunkCount;
        public DocumentVOBuilder() {}
        public DocumentVOBuilder id(String id) { this.id = id; return this; }
        public DocumentVOBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public DocumentVOBuilder filename(String filename) { this.filename = filename; return this; }
        public DocumentVOBuilder filetype(String filetype) { this.filetype = filetype; return this; }
        public DocumentVOBuilder size(Long size) { this.size = size; return this; }
        public DocumentVOBuilder indexStatus(String indexStatus) { this.indexStatus = indexStatus; return this; }
        public DocumentVOBuilder indexVersion(Integer indexVersion) { this.indexVersion = indexVersion; return this; }
        public DocumentVOBuilder chunkCount(Integer chunkCount) { this.chunkCount = chunkCount; return this; }
        public DocumentVO build() { return new DocumentVO(id, kbId, filename, filetype, size, indexStatus, indexVersion, chunkCount); }
    }
}

