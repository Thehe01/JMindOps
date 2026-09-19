package com.kama.jmindops.service;

import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.response.RagEvaluationComparisonResponse;
import com.kama.jmindops.service.rerank.NoopRagReranker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvaluationProvenanceServiceTest {
    @Test
    void attachesReproducibleEnvironmentMetadata() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        String kbId = "11111111-1111-1111-1111-111111111111";
        when(mapper.selectByKbId(kbId)).thenReturn(List.of(
                Document.builder().id("b").sourceKey("b.md").contentHash("hash-b")
                        .indexFingerprint("fingerprint").indexVersion(2).chunkCount(4).indexStatus("READY").build(),
                Document.builder().id("a").sourceKey("a.md").contentHash("hash-a")
                        .indexFingerprint("fingerprint").indexVersion(1).chunkCount(2).indexStatus("READY").build()
        ));
        RagEvaluationProvenanceService service =
                new RagEvaluationProvenanceService(
                        mapper, new NoopRagReranker(), fingerprint(),
                        "bge-m3", 1024, "none", "tika-flexmark-chunk-v1", "BAAI/bge-reranker-v2-m3",
                        5, 20, true, 0.05, 30, 30);

        RagEvaluationComparisonResponse response = service.attach(
                kbId,
                RagEvaluationComparisonResponse.builder().totalQueries(30).topK(3).build());

        assertThat(response.getEvaluationId()).isNotBlank();
        assertThat(response.getGeneratedAt()).isNotBlank();
        assertThat(response.getKnowledgeBaseId()).isEqualTo(kbId);
        assertThat(response.getKnowledgeBaseSnapshot()).hasSize(64);
        assertThat(response.getEmbeddingModel()).isEqualTo("bge-m3");
        assertThat(response.getRerankerProvider()).isEqualTo("none");
        assertThat(response.getRerankerModel()).isNull();
        assertThat(response.getRetrievalPipelineVersion()).isEqualTo("hybrid-rrf-v4");
        assertThat(response.getRetrievalConfig())
                .containsEntry("candidateTopK", 20)
                .containsEntry("minimumRerankScore", 0.05)
                .containsEntry("embeddingDimensions", 1024)
                .containsEntry("embeddingNormalization", "none")
                .containsEntry("indexPipelineVersion", "tika-flexmark-chunk-v1")
                .containsEntry("indexFingerprint", fingerprint().current());
        assertThat(response.getRetrievalConfigHash()).hasSize(64);
    }

    @Test
    void changesConfigHashWhenRetrievalParametersChange() {
        DocumentMapper mapper = mock(DocumentMapper.class);
        when(mapper.selectByKbId("kb")).thenReturn(List.of());
        RagEvaluationProvenanceService baseline = new RagEvaluationProvenanceService(
                mapper, new NoopRagReranker(), fingerprint(),
                "bge-m3", 1024, "none", "tika-flexmark-chunk-v1", "reranker",
                5, 20, true, 0.05, 30, 30);
        RagEvaluationProvenanceService changed = new RagEvaluationProvenanceService(
                mapper, new NoopRagReranker(), fingerprint(),
                "bge-m3", 1024, "none", "tika-flexmark-chunk-v1", "reranker",
                5, 10, true, 0.10, 30, 30);

        assertThat(baseline.describe("kb").getRetrievalConfigHash())
                .isNotEqualTo(changed.describe("kb").getRetrievalConfigHash());
    }

    private DocumentIndexFingerprint fingerprint() {
        return new DocumentIndexFingerprint("bge-m3", 1024, "none", "tika-flexmark-chunk-v1");
    }
}
