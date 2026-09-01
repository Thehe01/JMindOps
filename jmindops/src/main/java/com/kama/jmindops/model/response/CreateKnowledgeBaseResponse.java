package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateKnowledgeBaseResponse {
    private String knowledgeBaseId;

    public CreateKnowledgeBaseResponse() {}
    public CreateKnowledgeBaseResponse(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }
    public String getKnowledgeBaseId() { return this.knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public static CreateKnowledgeBaseResponseBuilder builder() { return new CreateKnowledgeBaseResponseBuilder(); }
    public static class CreateKnowledgeBaseResponseBuilder {
        private String knowledgeBaseId;
        public CreateKnowledgeBaseResponseBuilder() {}
        public CreateKnowledgeBaseResponseBuilder knowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; return this; }
        public CreateKnowledgeBaseResponse build() { return new CreateKnowledgeBaseResponse(knowledgeBaseId); }
    }
}

