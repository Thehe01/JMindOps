package com.kama.jmindops.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
public class RagComparisonEvaluationRequest {
    @NotBlank(message = "知识库 ID 不能为空")
    private String kbId;

    @NotEmpty(message = "至少需要一种评测模式")
    @Size(max = 3, message = "评测模式最多包含 3 种")
    private List<@NotNull RagEvaluationMode> modes;

    @Builder.Default
    @Min(value = 1, message = "topK 不能小于 1")
    @Max(value = 20, message = "topK 不能大于 20")
    private int topK = 3;

    @NotEmpty(message = "测试用例列表不能为空")
    @Size(max = 100, message = "单次评测最多包含 100 个测试用例")
    @Valid
    private List<RagBatchEvaluationRequest.TestCase> testCases;

    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public List<RagEvaluationMode> getModes() { return modes; }
    public void setModes(List<RagEvaluationMode> modes) { this.modes = modes; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public List<RagBatchEvaluationRequest.TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<RagBatchEvaluationRequest.TestCase> testCases) { this.testCases = testCases; }
}
