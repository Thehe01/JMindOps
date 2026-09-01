package com.kama.jmindops.model.response;

import com.kama.jmindops.model.vo.KnowledgeBaseVO;
import lombok.Builder;
import lombok.Data;
@Data
@Builder
public class GetKnowledgeBasesResponse {
    private KnowledgeBaseVO[] knowledgeBases;

    public GetKnowledgeBasesResponse() {}
    public GetKnowledgeBasesResponse(KnowledgeBaseVO[] knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    public KnowledgeBaseVO[] getKnowledgeBases() { return knowledgeBases; }
    public void setKnowledgeBases(KnowledgeBaseVO[] knowledgeBases) { this.knowledgeBases = knowledgeBases; }

    public static GetKnowledgeBasesResponseBuilder builder() { return new GetKnowledgeBasesResponseBuilder(); }
    public static class GetKnowledgeBasesResponseBuilder {
        private KnowledgeBaseVO[] knowledgeBases;
        public GetKnowledgeBasesResponseBuilder() {}
        public GetKnowledgeBasesResponseBuilder knowledgeBases(KnowledgeBaseVO[] knowledgeBases) { this.knowledgeBases = knowledgeBases; return this; }
        public GetKnowledgeBasesResponse build() { return new GetKnowledgeBasesResponse(knowledgeBases); }
    }
}
