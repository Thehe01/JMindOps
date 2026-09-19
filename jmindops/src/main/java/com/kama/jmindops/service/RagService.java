package com.kama.jmindops.service;

import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.request.RagEvaluationMode;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.model.response.RagEvaluationComparisonResponse;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);

    List<String> hybridSearch(String kbId, String title);

    List<RagSource> hybridSearchWithSources(String kbId, String query);

    RagBatchEvaluationResponse evaluateBatch(String kbId, List<RagBatchEvaluationRequest.TestCase> testCases);

    List<RagSource> searchForEvaluation(String kbId, String query, RagEvaluationMode mode, int topK);

    RagBatchEvaluationResponse evaluateBatch(
            String kbId,
            List<RagBatchEvaluationRequest.TestCase> testCases,
            RagEvaluationMode mode,
            int topK
    );

    RagEvaluationComparisonResponse compareEvaluationModes(
            String kbId,
            List<RagBatchEvaluationRequest.TestCase> testCases,
            List<RagEvaluationMode> modes,
            int topK
    );
}
