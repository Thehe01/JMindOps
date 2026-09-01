package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateDocumentResponse {
    private String documentId;

    public CreateDocumentResponse() {}
    public CreateDocumentResponse(String documentId) {
        this.documentId = documentId;
    }
    public String getDocumentId() { return this.documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public static CreateDocumentResponseBuilder builder() { return new CreateDocumentResponseBuilder(); }
    public static class CreateDocumentResponseBuilder {
        private String documentId;
        public CreateDocumentResponseBuilder() {}
        public CreateDocumentResponseBuilder documentId(String documentId) { this.documentId = documentId; return this; }
        public CreateDocumentResponse build() { return new CreateDocumentResponse(documentId); }
    }
}

