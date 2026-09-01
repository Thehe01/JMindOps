package com.kama.jmindops.service;

import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);

    List<String> hybridSearch(String kbId, String title);

    List<RagSource> hybridSearchWithSources(String kbId, String query);

    RagBatchEvaluationResponse evaluateBatch(String kbId, List<RagBatchEvaluationRequest.TestCase> testCases);
}
