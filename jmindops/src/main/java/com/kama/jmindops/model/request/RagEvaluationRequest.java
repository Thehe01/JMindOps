package com.kama.jmindops.model.request;

import com.kama.jmindops.validation.ValidationPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RagEvaluationRequest {
    @NotBlank(message = "知识库 ID 不能为空")
    @Pattern(regexp = ValidationPatterns.UUID, message = "知识库 ID 格式不正确")
    private String kbId;
    @NotBlank(message = "评测查询不能为空")
    @Size(max = 4_000, message = "评测查询不能超过 4000 个字符")
    private String query;
    @Pattern(regexp = ValidationPatterns.UUID, message = "期望文档 ID 格式不正确")
    private String expectedDocumentId;

    public RagEvaluationRequest() {}
    public RagEvaluationRequest(String kbId, String query, String expectedDocumentId) {
        this.kbId = kbId;
        this.query = query;
        this.expectedDocumentId = expectedDocumentId;
    }
    public String getKbId() { return this.kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getQuery() { return this.query; }
    public void setQuery(String query) { this.query = query; }
    public String getExpectedDocumentId() { return this.expectedDocumentId; }
    public void setExpectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; }
    public static RagEvaluationRequestBuilder builder() { return new RagEvaluationRequestBuilder(); }
    public static class RagEvaluationRequestBuilder {
        private String kbId;
        private String query;
        private String expectedDocumentId;
        public RagEvaluationRequestBuilder() {}
        public RagEvaluationRequestBuilder kbId(String kbId) { this.kbId = kbId; return this; }
        public RagEvaluationRequestBuilder query(String query) { this.query = query; return this; }
        public RagEvaluationRequestBuilder expectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; return this; }
        public RagEvaluationRequest build() { return new RagEvaluationRequest(kbId, query, expectedDocumentId); }
    }
}
