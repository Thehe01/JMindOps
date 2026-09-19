package com.kama.jmindops.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagBatchEvaluationRequest {
    @NotBlank(message = "知识库 ID 不能为空")
    private String kbId;

    @Builder.Default
    @NotNull(message = "评测模式不能为空")
    private RagEvaluationMode mode = RagEvaluationMode.HYBRID_RRF;

    @Builder.Default
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private int topK = 3;

    @NotEmpty(message = "测试用例列表不能为空")
    @Size(max = 100, message = "单次评测最多包含 100 个测试用例")
    @Valid
    private List<TestCase> testCases;

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public RagEvaluationMode getMode() { return mode; }
    public void setMode(RagEvaluationMode mode) { this.mode = mode; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> testCases) { this.testCases = testCases; }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestCase {
        @NotBlank(message = "查询文本不能为空")
        @Size(max = 2_000, message = "查询文本不能超过 2000 个字符")
        private String query;

        @Size(max = 64, message = "期望文档 ID 不能超过 64 个字符")
        private String expectedDocumentId;
        @Size(max = 500, message = "期望来源键不能超过 500 个字符")
        private String expectedSourceKey;
        @Size(max = 500, message = "期望关键词不能超过 500 个字符")
        private String expectedKeyword;
        @Size(max = 20, message = "期望文档最多配置 20 个")
        private List<@Size(max = 64, message = "期望文档 ID 不能超过 64 个字符") String> expectedDocumentIds;
        @Size(max = 20, message = "期望来源键最多配置 20 个")
        private List<@Size(max = 500, message = "期望来源键不能超过 500 个字符") String> expectedSourceKeys;
        @Size(max = 20, message = "期望关键词最多配置 20 个")
        private List<@Size(max = 500, message = "期望关键词不能超过 500 个字符") String> expectedKeywords;
        private Boolean expectedNoAnswer;

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getExpectedDocumentId() { return expectedDocumentId; }
        public void setExpectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; }
        public String getExpectedSourceKey() { return expectedSourceKey; }
        public void setExpectedSourceKey(String expectedSourceKey) { this.expectedSourceKey = expectedSourceKey; }
        public String getExpectedKeyword() { return expectedKeyword; }
        public void setExpectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; }
        public List<String> getExpectedDocumentIds() { return expectedDocumentIds; }
        public void setExpectedDocumentIds(List<String> expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; }
        public List<String> getExpectedSourceKeys() { return expectedSourceKeys; }
        public void setExpectedSourceKeys(List<String> expectedSourceKeys) { this.expectedSourceKeys = expectedSourceKeys; }
        public List<String> getExpectedKeywords() { return expectedKeywords; }
        public void setExpectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; }
        public Boolean getExpectedNoAnswer() { return expectedNoAnswer; }
        public void setExpectedNoAnswer(Boolean expectedNoAnswer) { this.expectedNoAnswer = expectedNoAnswer; }

        @AssertTrue(message = "每个测试用例至少需要期望文档、关键词或 expectedNoAnswer=true")
        public boolean isExpectationConfigured() {
            return hasText(expectedDocumentId)
                    || hasText(expectedSourceKey)
                    || hasText(expectedKeyword)
                    || hasValues(expectedDocumentIds)
                    || hasValues(expectedSourceKeys)
                    || hasValues(expectedKeywords)
                    || Boolean.TRUE.equals(expectedNoAnswer);
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        private boolean hasValues(List<String> values) {
            return values != null && values.stream().anyMatch(this::hasText);
        }
    }
}
