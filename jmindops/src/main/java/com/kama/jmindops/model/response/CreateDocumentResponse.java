package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateDocumentResponse {
    private String documentId;
    private String indexAction;
    private Integer indexVersion;
    private Integer chunkCount;
    private Integer reusedChunkCount;
    private Integer embeddedChunkCount;

    public CreateDocumentResponse() {}
    public CreateDocumentResponse(String documentId, String indexAction, Integer indexVersion, Integer chunkCount, Integer reusedChunkCount, Integer embeddedChunkCount) {
        this.documentId = documentId;
        this.indexAction = indexAction;
        this.indexVersion = indexVersion;
        this.chunkCount = chunkCount;
        this.reusedChunkCount = reusedChunkCount;
        this.embeddedChunkCount = embeddedChunkCount;
    }
    public String getDocumentId() { return this.documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getIndexAction() { return indexAction; }
    public void setIndexAction(String indexAction) { this.indexAction = indexAction; }
    public Integer getIndexVersion() { return indexVersion; }
    public void setIndexVersion(Integer indexVersion) { this.indexVersion = indexVersion; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public Integer getReusedChunkCount() { return reusedChunkCount; }
    public void setReusedChunkCount(Integer reusedChunkCount) { this.reusedChunkCount = reusedChunkCount; }
    public Integer getEmbeddedChunkCount() { return embeddedChunkCount; }
    public void setEmbeddedChunkCount(Integer embeddedChunkCount) { this.embeddedChunkCount = embeddedChunkCount; }
    public static CreateDocumentResponseBuilder builder() { return new CreateDocumentResponseBuilder(); }
    public static class CreateDocumentResponseBuilder {
        private String documentId;
        private String indexAction;
        private Integer indexVersion;
        private Integer chunkCount;
        private Integer reusedChunkCount;
        private Integer embeddedChunkCount;
        public CreateDocumentResponseBuilder() {}
        public CreateDocumentResponseBuilder documentId(String documentId) { this.documentId = documentId; return this; }
        public CreateDocumentResponseBuilder indexAction(String indexAction) { this.indexAction = indexAction; return this; }
        public CreateDocumentResponseBuilder indexVersion(Integer indexVersion) { this.indexVersion = indexVersion; return this; }
        public CreateDocumentResponseBuilder chunkCount(Integer chunkCount) { this.chunkCount = chunkCount; return this; }
        public CreateDocumentResponseBuilder reusedChunkCount(Integer reusedChunkCount) { this.reusedChunkCount = reusedChunkCount; return this; }
        public CreateDocumentResponseBuilder embeddedChunkCount(Integer embeddedChunkCount) { this.embeddedChunkCount = embeddedChunkCount; return this; }
        public CreateDocumentResponse build() { return new CreateDocumentResponse(documentId, indexAction, indexVersion, chunkCount, reusedChunkCount, embeddedChunkCount); }
    }
}

