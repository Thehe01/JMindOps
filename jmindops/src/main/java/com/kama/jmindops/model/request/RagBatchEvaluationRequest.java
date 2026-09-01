package com.kama.jmindops.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class RagBatchEvaluationRequest {
    @NotBlank(message = "知识库 ID 不能为空")
    private String kbId;

    @NotEmpty(message = "测试用例列表不能为空")
    @Valid
    private List<TestCase> testCases;

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }

    @Data
    @Builder
    public static class TestCase {
        @NotBlank(message = "查询文本不能为空")
        private String query;

        private String expectedDocumentId;
        private String expectedKeyword;

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getExpectedDocumentId() { return expectedDocumentId; }
        public void setExpectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; }
        public String getExpectedKeyword() { return expectedKeyword; }
        public void setExpectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; }
    }
}
