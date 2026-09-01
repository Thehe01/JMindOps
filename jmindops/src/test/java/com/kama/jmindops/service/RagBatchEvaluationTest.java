package com.kama.jmindops.service;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.service.impl.RagServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class RagBatchEvaluationTest {

    private ChunkBgeM3Mapper chunkBgeM3Mapper;
    private RagService ragService;

    @BeforeEach
    void setUp() {
        chunkBgeM3Mapper = Mockito.mock(ChunkBgeM3Mapper.class);
        WebClient.Builder builder = WebClient.builder();
        ragService = new RagServiceImpl(
                builder,
                chunkBgeM3Mapper,
                "http://localhost:11434",
                "bge-m3",
                5,
                false,
                "bge-reranker-large"
        );
    }

    @Test
    void calculatesHitRateAndMrrCorrectlyForBatchQueries() {
        // Mocking RAG service with custom anonymous subclass to test pure calculation without needing live embedding HTTP
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                "http://localhost:11434",
                "bge-m3",
                5,
                false,
                "bge-reranker-large"
        ) {
            @Override
            public List<RagSource> hybridSearchWithSources(String kbId, String query) {
                if (query.contains("PostgreSQL")) {
                    return List.of(
                            new RagSource("doc-1", "PostgreSQL pgvector 是向量检索插件"),
                            new RagSource("doc-2", "Redis 是高性能缓存")
                    );
                } else if (query.contains("Spring")) {
                    return List.of(
                            new RagSource("doc-3", "Spring Boot 自动配置"),
                            new RagSource("doc-4", "Spring AI 提供统一的大模型抽象")
                    );
                } else {
                    return List.of(
                            new RagSource("doc-5", "无关文档内容")
                    );
                }
            }
        };

        List<RagBatchEvaluationRequest.TestCase> testCases = List.of(
                // Case 1: Hit at Rank 1 (Doc ID doc-1) -> RR = 1.0
                RagBatchEvaluationRequest.TestCase.builder()
                        .query("什么是 PostgreSQL pgvector？")
                        .expectedDocumentId("doc-1")
                        .build(),
                // Case 2: Hit at Rank 2 (Keyword "Spring AI") -> RR = 0.5
                RagBatchEvaluationRequest.TestCase.builder()
                        .query("Spring 框架如何调用大模型？")
                        .expectedKeyword("Spring AI")
                        .build(),
                // Case 3: Miss -> RR = 0.0
                RagBatchEvaluationRequest.TestCase.builder()
                        .query("微服务网关如何配置？")
                        .expectedDocumentId("doc-99")
                        .build()
        );

        RagBatchEvaluationResponse response = customRagService.evaluateBatch("kb-1", testCases);

        assertThat(response).isNotNull();
        assertThat(response.getTotalQueries()).isEqualTo(3);
        assertThat(response.getHitCount()).isEqualTo(2);
        // HitRate = 2 / 3 = 66.67%
        assertThat(response.getHitRate()).isEqualTo(66.67);
        // MRR = (1.0 + 0.5 + 0.0) / 3 = 0.5
        assertThat(response.getMrr()).isEqualTo(0.5);
        assertThat(response.getDetails()).hasSize(3);
        assertThat(response.getDetails().get(0).isHit()).isTrue();
        assertThat(response.getDetails().get(0).getRank()).isEqualTo(1);
        assertThat(response.getDetails().get(1).isHit()).isTrue();
        assertThat(response.getDetails().get(1).getRank()).isEqualTo(2);
        assertThat(response.getDetails().get(2).isHit()).isFalse();
    }
}
