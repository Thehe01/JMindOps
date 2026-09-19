package com.kama.jmindops.service;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.request.RagEvaluationMode;
import com.kama.jmindops.model.request.RagEvaluationStatus;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.model.response.RagEvaluationComparisonResponse;
import com.kama.jmindops.service.impl.RagServiceImpl;
import com.kama.jmindops.service.rerank.NoopRagReranker;
import com.kama.jmindops.service.rerank.RagReranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

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
                new NoopRagReranker(),
                "http://localhost:11434",
                "bge-m3",
                5
        );
    }

    @Test
    void calculatesHitRateAndMrrCorrectlyForBatchQueries() {
        // Mocking RAG service with custom anonymous subclass to test pure calculation without needing live embedding HTTP
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(),
                chunkBgeM3Mapper,
                new NoopRagReranker(),
                "http://localhost:11434",
                "bge-m3",
                5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(String kbId, String query, RagEvaluationMode mode, int topK) {
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
        assertThat(response.getTopK()).isEqualTo(3);
        assertThat(response.getRecallAtK()).isEqualTo(66.67);
        assertThat(response.getPrecisionAtK()).isEqualTo(22.22);
        assertThat(response.getAverageReturnedSources()).isEqualTo(1.67);
        assertThat(response.getP50LatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(response.getP95LatencyMs()).isGreaterThanOrEqualTo(response.getP50LatencyMs());
        // MRR = (1.0 + 0.5 + 0.0) / 3 = 0.5
        assertThat(response.getMrr()).isEqualTo(0.5);
        assertThat(response.getDetails()).hasSize(3);
        assertThat(response.getDetails().get(0).isHit()).isTrue();
        assertThat(response.getDetails().get(0).getRank()).isEqualTo(1);
        assertThat(response.getDetails().get(0).getRetrievedSourceIds()).containsExactly("doc-1", "doc-2");
        assertThat(response.getDetails().get(1).isHit()).isTrue();
        assertThat(response.getDetails().get(1).getRank()).isEqualTo(2);
        assertThat(response.getDetails().get(2).isHit()).isFalse();
    }

    @Test
    void calculatesRecallAcrossMultipleExpectedTargets() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(String kbId, String query, RagEvaluationMode mode, int topK) {
                return List.of(
                        new RagSource("doc-1", "alpha content with beta"),
                        new RagSource("doc-2", "contains beta")
                );
            }
        };
        RagBatchEvaluationRequest.TestCase testCase = RagBatchEvaluationRequest.TestCase.builder()
                .query("multi target")
                .expectedDocumentIds(List.of("doc-1", "doc-3"))
                .expectedKeywords(List.of("beta"))
                .build();

        RagBatchEvaluationResponse response = customRagService.evaluateBatch("kb-1", List.of(testCase));

        assertThat(response.getRecallAtK()).isEqualTo(66.67);
        assertThat(response.getPrecisionAtK()).isEqualTo(33.33);
        assertThat(response.getMrr()).isEqualTo(1.0);
        assertThat(response.getDetails().get(0).getExpectedCount()).isEqualTo(3);
        assertThat(response.getDetails().get(0).getMatchedExpectedCount()).isEqualTo(2);
    }

    @Test
    void scoresStableDocumentSourceKeysWithoutRuntimeDocumentIds() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(
                    String kbId, String query, RagEvaluationMode mode, int topK) {
                return List.of(new RagSource("runtime-uuid", "readme.md", "expected evidence", null));
            }
        };
        RagBatchEvaluationRequest.TestCase testCase = RagBatchEvaluationRequest.TestCase.builder()
                .query("source key")
                .expectedSourceKey("README.md")
                .expectedKeyword("evidence")
                .build();

        RagEvaluationComparisonResponse response = customRagService.compareEvaluationModes(
                "kb", List.of(testCase), List.of(RagEvaluationMode.VECTOR), 3);

        assertThat(response.getRelevanceLabelType()).isEqualTo("SOURCE_KEY_AND_KEYWORD");
        assertThat(response.getResults().get(0).getHitRate()).isEqualTo(100.0);
        assertThat(response.getResults().get(0).getDetails().get(0).getRetrievedSourceKeys())
                .containsExactly("readme.md");
    }

    @Test
    void treatsExpectedSourceKeysAsAlternativesInsteadOfIndependentRequirements() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(
                    String kbId, String query, RagEvaluationMode mode, int topK) {
                return List.of(new RagSource(
                        "runtime-id", "全生命周期.md", "包含预期证据", null));
            }
        };
        RagBatchEvaluationRequest.TestCase testCase = RagBatchEvaluationRequest.TestCase.builder()
                .query("alternative sources")
                .expectedSourceKey("README.md")
                .expectedSourceKeys(List.of("README.md", "全生命周期.md"))
                .expectedKeyword("预期证据")
                .build();

        RagBatchEvaluationResponse response = customRagService.evaluateBatch("kb", List.of(testCase));

        assertThat(response.getHitRate()).isEqualTo(100.0);
        assertThat(response.getRecallAtK()).isEqualTo(100.0);
        assertThat(response.getDetails().get(0).getExpectedCount()).isEqualTo(2);
        assertThat(response.getDetails().get(0).getMatchedExpectedCount()).isEqualTo(2);
    }

    @Test
    void doesNotCountAnUnrelatedChunkFromTheExpectedSourceAsAHit() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(
                    String kbId, String query, RagEvaluationMode mode, int topK) {
                return List.of(new RagSource("runtime-uuid", "readme.md", "unrelated chunk", null));
            }
        };
        RagBatchEvaluationRequest.TestCase testCase = RagBatchEvaluationRequest.TestCase.builder()
                .query("source key without evidence")
                .expectedSourceKey("README.md")
                .expectedKeyword("expected evidence")
                .build();

        RagBatchEvaluationResponse response = customRagService.evaluateBatch("kb", List.of(testCase));

        assertThat(response.getHitRate()).isZero();
        assertThat(response.getMrr()).isZero();
        assertThat(response.getDetails().get(0).isHit()).isFalse();
    }

    @Test
    void comparesModesWithTheSameTopKAndIsolatesRerankerFailure() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(
                    String kbId, String query, RagEvaluationMode mode, int topK) {
                return switch (mode) {
                    case VECTOR -> List.of(
                            new RagSource("noise", "unrelated"),
                            new RagSource("target", "expected answer")
                    );
                    case HYBRID_RRF -> List.of(
                            new RagSource("target", "expected answer"),
                            new RagSource("noise", "unrelated")
                    );
                    case HYBRID_RERANK -> throw new RuntimeException("reranker unavailable");
                };
            }
        };

        RagBatchEvaluationRequest.TestCase testCase = RagBatchEvaluationRequest.TestCase.builder()
                .query("comparison")
                .expectedDocumentId("target")
                .build();

        RagEvaluationComparisonResponse response = customRagService.compareEvaluationModes(
                "kb-1",
                List.of(testCase),
                List.of(
                        RagEvaluationMode.VECTOR,
                        RagEvaluationMode.HYBRID_RRF,
                        RagEvaluationMode.HYBRID_RERANK
                ),
                2
        );

        assertThat(response.getTopK()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults().get(0).getMode()).isEqualTo(RagEvaluationMode.VECTOR);
        assertThat(response.getResults().get(0).getMrr()).isEqualTo(0.5);
        assertThat(response.getResults().get(1).getMode()).isEqualTo(RagEvaluationMode.HYBRID_RRF);
        assertThat(response.getResults().get(1).getMrr()).isEqualTo(1.0);
        assertThat(response.getResults().get(2).isSuccessful()).isFalse();
        assertThat(response.getResults().get(2).getError()).contains("reranker unavailable");
    }

    @Test
    void marksUndeployedRerankerAsNotConfiguredWithoutCallingEmbedding() {
        RagEvaluationComparisonResponse response = ragService.compareEvaluationModes(
                "kb-1",
                List.of(RagBatchEvaluationRequest.TestCase.builder()
                        .query("comparison")
                        .expectedKeyword("target")
                        .build()),
                List.of(RagEvaluationMode.HYBRID_RERANK),
                3
        );

        assertThat(response.getRelevanceLabelType()).isEqualTo("KEYWORD_PROXY");

        assertThat(response.getResults()).singleElement().satisfies(result -> {
            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.getStatus()).isEqualTo(RagEvaluationStatus.NOT_CONFIGURED);
            assertThat(result.getError()).contains("未配置");
        });
        Mockito.verifyNoInteractions(chunkBgeM3Mapper);
    }

    @Test
    void scoresNoAnswerQueriesSeparatelyFromPositiveRetrievalMetrics() {
        RagService customRagService = new RagServiceImpl(
                WebClient.builder(), chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5
        ) {
            @Override
            public List<RagSource> searchForEvaluation(
                    String kbId, String query, RagEvaluationMode mode, int topK) {
                return query.equals("empty no answer")
                        ? List.of()
                        : List.of(new RagSource("noise", "irrelevant"));
            }
        };
        List<RagBatchEvaluationRequest.TestCase> cases = List.of(
                RagBatchEvaluationRequest.TestCase.builder()
                        .query("empty no answer")
                        .expectedNoAnswer(true)
                        .build(),
                RagBatchEvaluationRequest.TestCase.builder()
                        .query("non-empty no answer")
                        .expectedNoAnswer(true)
                        .build()
        );

        RagBatchEvaluationResponse response = customRagService.evaluateBatch(
                "kb-1", cases, RagEvaluationMode.VECTOR, 3);

        assertThat(response.getPositiveQueryCount()).isZero();
        assertThat(response.getNoAnswerQueryCount()).isEqualTo(2);
        assertThat(response.getNoAnswerCorrectCount()).isEqualTo(1);
        assertThat(response.getNoAnswerAccuracy()).isEqualTo(50.0);
        assertThat(response.getHitRate()).isZero();
        assertThat(response.getDetails()).extracting(
                RagBatchEvaluationResponse.DetailResult::getNoAnswerCorrect)
                .containsExactly(true, false);
    }

    @Test
    void filtersLowConfidenceRerankerResultsAndExpandsCandidateRecall() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embedding\":[0.1,0.2]}")
                        .build()));
        RagReranker scoringReranker = new RagReranker() {
            @Override
            public String providerId() {
                return "test";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public List<RagSource> rerank(String query, List<RagSource> candidates, int topK) {
                return candidates.stream()
                        .limit(topK)
                        .map(source -> new RagSource(
                                source.documentId(), source.content(), 0.001))
                        .toList();
            }
        };
        RagService service = new RagServiceImpl(
                builder, chunkBgeM3Mapper, scoringReranker,
                "http://localhost:11434", "bge-m3", 5);
        ChunkBgeM3 weakChunk = ChunkBgeM3.builder()
                .docId("noise")
                .content("irrelevant")
                .build();
        when(chunkBgeM3Mapper.similaritySearch(eq("kb-1"), anyString(), anyString(), eq(20)))
                .thenReturn(List.of(weakChunk));
        when(chunkBgeM3Mapper.bm25Search(eq("kb-1"), eq("unknown"), anyString(), eq(20)))
                .thenReturn(List.of(weakChunk));

        assertThat(service.searchForEvaluation(
                "kb-1", "unknown", RagEvaluationMode.HYBRID_RERANK, 3)).isEmpty();
    }

    @Test
    void rrfKeepsIdenticalTextFromDifferentDocumentsAsDistinctSources() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"embedding\":[0.1,0.2]}")
                        .build()));
        RagService service = new RagServiceImpl(
                builder, chunkBgeM3Mapper, new NoopRagReranker(),
                "http://localhost:11434", "bge-m3", 5);
        ChunkBgeM3 first = ChunkBgeM3.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .docId("doc-1")
                .sourceKey("first.md")
                .content("shared boilerplate")
                .chunkIndex(0)
                .build();
        ChunkBgeM3 second = ChunkBgeM3.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .docId("doc-2")
                .sourceKey("second.md")
                .content("shared boilerplate")
                .chunkIndex(0)
                .build();
        when(chunkBgeM3Mapper.similaritySearch(eq("kb-1"), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(first));
        when(chunkBgeM3Mapper.bm25Search(eq("kb-1"), eq("shared"), anyString(), anyInt()))
                .thenReturn(List.of(second));

        List<RagSource> sources = service.searchForEvaluation(
                "kb-1", "shared", RagEvaluationMode.HYBRID_RRF, 2);

        assertThat(sources).extracting(RagSource::documentId)
                .containsExactlyInAnyOrder("doc-1", "doc-2");
    }
}
