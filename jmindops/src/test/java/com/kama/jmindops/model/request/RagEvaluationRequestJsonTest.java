package com.kama.jmindops.model.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvaluationRequestJsonTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appliesBackwardCompatibleBatchDefaultsWhenFieldsAreMissing() throws Exception {
        RagBatchEvaluationRequest request = objectMapper.readValue(
                """
                {
                  "kbId": "kb-1",
                  "testCases": [
                    {
                      "query": "what is pgvector",
                      "expectedKeywords": ["pgvector"]
                    }
                  ]
                }
                """,
                RagBatchEvaluationRequest.class
        );

        assertThat(request.getMode()).isEqualTo(RagEvaluationMode.HYBRID_RRF);
        assertThat(request.getTopK()).isEqualTo(3);
        assertThat(request.getTestCases()).hasSize(1);
    }

    @Test
    void deserializesComparisonModesAndTopK() throws Exception {
        RagComparisonEvaluationRequest request = objectMapper.readValue(
                """
                {
                  "kbId": "kb-1",
                  "modes": ["VECTOR", "HYBRID_RRF", "HYBRID_RERANK"],
                  "topK": 5,
                  "testCases": [
                    {
                      "query": "what is pgvector",
                      "expectedDocumentIds": ["doc-1"]
                    }
                  ]
                }
                """,
                RagComparisonEvaluationRequest.class
        );

        assertThat(request.getModes()).containsExactly(
                RagEvaluationMode.VECTOR,
                RagEvaluationMode.HYBRID_RRF,
                RagEvaluationMode.HYBRID_RERANK
        );
        assertThat(request.getTopK()).isEqualTo(5);
    }
}
