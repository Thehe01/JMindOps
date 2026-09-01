package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.DocumentVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class GetDocumentsResponse {
    private DocumentVO[] documents;

    public GetDocumentsResponse() {}
    public GetDocumentsResponse(DocumentVO[] documents) {
        this.documents = documents;
    }

    public DocumentVO[] getDocuments() { return documents; }
    public void setDocuments(DocumentVO[] documents) { this.documents = documents; }

    public static GetDocumentsResponseBuilder builder() { return new GetDocumentsResponseBuilder(); }
    public static class GetDocumentsResponseBuilder {
        private DocumentVO[] documents;
        public GetDocumentsResponseBuilder() {}
        public GetDocumentsResponseBuilder documents(DocumentVO[] documents) { this.documents = documents; return this; }
        public GetDocumentsResponse build() { return new GetDocumentsResponse(documents); }
    }
}
