package com.kama.jmindops.service.impl;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.request.RagEvaluationMode;
import com.kama.jmindops.model.request.RagEvaluationStatus;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.model.response.RagEvaluationComparisonResponse;
import com.kama.jmindops.service.RagService;
import com.kama.jmindops.service.RagSource;
import com.kama.jmindops.service.DocumentHashing;
import com.kama.jmindops.service.DocumentIndexFingerprint;
import com.kama.jmindops.service.rerank.RagReranker;
import com.kama.jmindops.service.rerank.RerankerNotConfiguredException;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class RagServiceImpl implements RagService {
    private static final int DEFAULT_SEARCH_TOP_K = 5;
    private static final int DEFAULT_CANDIDATE_TOP_K = 20;
    private static final int DEFAULT_EVALUATION_TOP_K = 3;
    private static final int MAX_EVALUATION_TOP_K = 20;

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final RagReranker ragReranker;
    private final String embeddingModel;
    private final DocumentIndexFingerprint indexFingerprint;
    private final int requestTimeoutSeconds;
    @Value("${rag.retrieval.answer-top-k:5}")
    private int answerTopK = DEFAULT_SEARCH_TOP_K;
    @Value("${rag.retrieval.candidate-top-k:20}")
    private int candidateTopK = DEFAULT_CANDIDATE_TOP_K;
    @Value("${rag.retrieval.evidence-gate-enabled:true}")
    private boolean evidenceGateEnabled = true;
    @Value("${rag.retrieval.minimum-rerank-score:0.05}")
    private double minimumRerankScore = 0.05;

    @Autowired
    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            RagReranker ragReranker,
            @Value("${rag.embedding.base-url}") String embeddingBaseUrl,
            @Value("${rag.embedding.model}") String embeddingModel,
            @Value("${rag.embedding.request-timeout-seconds}") int requestTimeoutSeconds,
            DocumentIndexFingerprint indexFingerprint
    ) {
        this.webClient = builder.baseUrl(embeddingBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.ragReranker = ragReranker;
        this.embeddingModel = embeddingModel;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.indexFingerprint = indexFingerprint;
    }

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            RagReranker ragReranker,
            String embeddingBaseUrl,
            String embeddingModel,
            int requestTimeoutSeconds
    ) {
        this(builder, chunkBgeM3Mapper, ragReranker, embeddingBaseUrl, embeddingModel,
                requestTimeoutSeconds,
                new DocumentIndexFingerprint(embeddingModel, 1024, "none", "tika-flexmark-chunk-v1"));
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", embeddingModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block(Duration.ofSeconds(requestTimeoutSeconds));
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return vectorSearchWithSources(kbId, title, validatedAnswerTopK()).stream()
                .map(RagSource::content)
                .toList();
    }

    @Override
    public List<String> hybridSearch(String kbId, String title) {
        return hybridSearchWithSources(kbId, title).stream().map(RagSource::content).toList();
    }

    @Override
    public List<RagSource> hybridSearchWithSources(String kbId, String query) {
        return hybridSearchWithSources(
                kbId, query, validatedAnswerTopK(), ragReranker.isConfigured(), true);
    }

    @Override
    public List<RagSource> searchForEvaluation(
            String kbId,
            String query,
            RagEvaluationMode mode,
            int topK
    ) {
        int validatedTopK = validateTopK(topK);
        RagEvaluationMode effectiveMode = mode == null ? RagEvaluationMode.HYBRID_RRF : mode;
        return switch (effectiveMode) {
            case VECTOR -> vectorSearchWithSources(kbId, query, validatedTopK);
            case HYBRID_RRF -> hybridSearchWithSources(
                    kbId, query, validatedTopK, false, false);
            case HYBRID_RERANK -> {
                if (!ragReranker.isConfigured()) {
                    throw new RerankerNotConfiguredException(
                            "Reranker Provider 未配置（当前 provider=" + ragReranker.providerId() + "）");
                }
                yield hybridSearchWithSources(kbId, query, validatedTopK, true, false);
            }
        };
    }

    private List<RagSource> vectorSearchWithSources(String kbId, String query, int topK) {
        String queryEmbedding = toPgVector(doEmbed(query));
        return chunkBgeM3Mapper.similaritySearch(
                        kbId, queryEmbedding, indexFingerprint.current(), topK).stream()
                .map(chunk -> new RagSource(
                        chunk.getDocId(), chunk.getSourceKey(), chunk.getContent(), null))
                .toList();
    }

    private List<RagSource> hybridSearchWithSources(
            String kbId,
            String query,
            int topK,
            boolean applyReranker,
            boolean allowRerankerFallback
    ) {
        int retrievalLimit = applyReranker
                ? Math.max(topK, Math.min(candidateTopK, MAX_EVALUATION_TOP_K))
                : topK;
        String queryEmbedding = toPgVector(doEmbed(query));
        List<ChunkBgeM3> vectorChunks =
                chunkBgeM3Mapper.similaritySearch(
                        kbId, queryEmbedding, indexFingerprint.current(), retrievalLimit);
        List<ChunkBgeM3> bm25Chunks =
                chunkBgeM3Mapper.bm25Search(
                        kbId, query, indexFingerprint.current(), Math.max(5, retrievalLimit));

        Map<String, ChunkBgeM3> uniqueChunks = new LinkedHashMap<>();
        vectorChunks.forEach(chunk -> uniqueChunks.putIfAbsent(chunkIdentity(chunk), chunk));
        bm25Chunks.forEach(chunk -> uniqueChunks.putIfAbsent(chunkIdentity(chunk), chunk));

        List<String> orderedChunkKeys = reciprocalRankFusion(vectorChunks, bm25Chunks);
        List<RagSource> fusedCandidates = orderedChunkKeys.stream()
                .map(uniqueChunks::get)
                .map(chunk -> new RagSource(
                        chunk.getDocId(), chunk.getSourceKey(), chunk.getContent(), null))
                .toList();
        if (applyReranker && !fusedCandidates.isEmpty()) {
            try {
                if (!ragReranker.isConfigured()) {
                    throw new RerankerNotConfiguredException(
                            "Reranker Provider 未配置（当前 provider=" + ragReranker.providerId() + "）");
                }
                return applyEvidenceGate(ragReranker.rerank(query, fusedCandidates, topK));
            } catch (Exception e) {
                if (!allowRerankerFallback) {
                    throw e;
                }
                org.slf4j.LoggerFactory.getLogger(RagServiceImpl.class)
                        .warn("Reranker 接口调用失败，触发 RRF 降级策略: {}", e.getMessage());
            }
        }

        return fusedCandidates.stream()
                .limit(topK)
                .toList();
    }

    private List<RagSource> applyEvidenceGate(List<RagSource> rankedSources) {
        if (!evidenceGateEnabled || rankedSources == null || rankedSources.isEmpty()) {
            return rankedSources == null ? List.of() : rankedSources;
        }
        return rankedSources.stream()
                .filter(source -> source.relevanceScore() == null
                        || source.relevanceScore() >= minimumRerankScore)
                .toList();
    }

    private int validatedAnswerTopK() {
        return Math.max(1, Math.min(answerTopK, MAX_EVALUATION_TOP_K));
    }

    /**
     * 使用 Reciprocal Rank Fusion 融合向量与 BM25 排名。
     * 这也是 reranker 关闭/失败时的确定性降级路径，避免某一路候选因插入顺序被全部截断。
     */
    private List<String> reciprocalRankFusion(
            List<ChunkBgeM3> vectorChunks,
            List<ChunkBgeM3> bm25Chunks
    ) {
        final int rankConstant = 60;
        Map<String, Double> scores = new HashMap<>();
        addRankScores(scores, vectorChunks, rankConstant);
        addRankScores(scores, bm25Chunks, rankConstant);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addRankScores(
            Map<String, Double> scores,
            List<ChunkBgeM3> rankedChunks,
            int rankConstant
    ) {
        for (int index = 0; index < rankedChunks.size(); index++) {
            String chunkKey = chunkIdentity(rankedChunks.get(index));
            scores.merge(chunkKey, 1.0 / (rankConstant + index + 1), Double::sum);
        }
    }

    private String chunkIdentity(ChunkBgeM3 chunk) {
        if (chunk.getId() != null && !chunk.getId().isBlank()) {
            return "id:" + chunk.getId();
        }
        String documentId = chunk.getDocId() == null ? "" : chunk.getDocId();
        if (!documentId.isBlank() && chunk.getChunkIndex() != null) {
            return "document:" + documentId + ":index:" + chunk.getChunkIndex();
        }
        String chunkHash = chunk.getChunkHash() == null
                ? DocumentHashing.sha256(String.valueOf(chunk.getContent()))
                : chunk.getChunkHash();
        return "document:" + documentId + ":hash:" + chunkHash;
    }

    @Override
    public RagBatchEvaluationResponse evaluateBatch(String kbId, List<RagBatchEvaluationRequest.TestCase> testCases) {
        return evaluateBatch(
                kbId, testCases, RagEvaluationMode.HYBRID_RRF, DEFAULT_EVALUATION_TOP_K);
    }

    @Override
    public RagBatchEvaluationResponse evaluateBatch(
            String kbId,
            List<RagBatchEvaluationRequest.TestCase> testCases,
            RagEvaluationMode mode,
            int topK
    ) {
        RagEvaluationMode effectiveMode = mode == null ? RagEvaluationMode.HYBRID_RRF : mode;
        int validatedTopK = validateTopK(topK);
        return evaluateBatch(
                testCases,
                effectiveMode,
                validatedTopK,
                query -> searchForEvaluation(kbId, query, effectiveMode, validatedTopK)
        );
    }

    @Override
    public RagEvaluationComparisonResponse compareEvaluationModes(
            String kbId,
            List<RagBatchEvaluationRequest.TestCase> testCases,
            List<RagEvaluationMode> modes,
            int topK
    ) {
        int validatedTopK = validateTopK(topK);
        Set<RagEvaluationMode> uniqueModes = new LinkedHashSet<>();
        if (modes != null) {
            modes.stream().filter(mode -> mode != null).forEach(uniqueModes::add);
        }

        List<RagBatchEvaluationResponse> results = uniqueModes.stream()
                .map(mode -> evaluateModeSafely(kbId, testCases, mode, validatedTopK))
                .toList();
        return RagEvaluationComparisonResponse.builder()
                .totalQueries(testCases == null ? 0 : testCases.size())
                .topK(validatedTopK)
                .relevanceLabelType(relevanceLabelType(testCases))
                .results(results)
                .build();
    }

    private String relevanceLabelType(List<RagBatchEvaluationRequest.TestCase> testCases) {
        if (testCases == null) {
            return "UNLABELED";
        }
        boolean hasDocumentIds = testCases.stream()
                .filter(testCase -> !Boolean.TRUE.equals(testCase.getExpectedNoAnswer()))
                .anyMatch(testCase -> !expectedValues(
                        testCase.getExpectedDocumentId(), testCase.getExpectedDocumentIds()).isEmpty());
        boolean hasSourceKeys = testCases.stream()
                .filter(testCase -> !Boolean.TRUE.equals(testCase.getExpectedNoAnswer()))
                .anyMatch(testCase -> !expectedValues(
                        testCase.getExpectedSourceKey(), testCase.getExpectedSourceKeys()).isEmpty());
        boolean hasKeywords = testCases.stream()
                .filter(testCase -> !Boolean.TRUE.equals(testCase.getExpectedNoAnswer()))
                .anyMatch(testCase -> !expectedValues(
                        testCase.getExpectedKeyword(), testCase.getExpectedKeywords()).isEmpty());
        if ((hasDocumentIds || hasSourceKeys) && hasKeywords) {
            if (hasDocumentIds && hasSourceKeys) return "DOCUMENT_ID_SOURCE_KEY_AND_KEYWORD";
            return hasDocumentIds ? "DOCUMENT_ID_AND_KEYWORD" : "SOURCE_KEY_AND_KEYWORD";
        }
        if (hasDocumentIds) return "DOCUMENT_ID";
        if (hasSourceKeys) return "SOURCE_KEY";
        if (hasKeywords) return "KEYWORD_PROXY";
        return "UNLABELED";
    }

    private RagBatchEvaluationResponse evaluateModeSafely(
            String kbId,
            List<RagBatchEvaluationRequest.TestCase> testCases,
            RagEvaluationMode mode,
            int topK
    ) {
        try {
            return evaluateBatch(kbId, testCases, mode, topK);
        } catch (RerankerNotConfiguredException exception) {
            return RagBatchEvaluationResponse.builder()
                    .mode(mode)
                    .status(RagEvaluationStatus.NOT_CONFIGURED)
                    .successful(false)
                    .error(safeEvaluationError(exception))
                    .totalQueries(testCases == null ? 0 : testCases.size())
                    .topK(topK)
                    .details(List.of())
                    .build();
        } catch (RuntimeException exception) {
            return RagBatchEvaluationResponse.builder()
                    .mode(mode)
                    .status(RagEvaluationStatus.FAILED)
                    .successful(false)
                    .error(safeEvaluationError(exception))
                    .totalQueries(testCases == null ? 0 : testCases.size())
                    .topK(topK)
                    .details(List.of())
                    .build();
        }
    }

    private RagBatchEvaluationResponse evaluateBatch(
            List<RagBatchEvaluationRequest.TestCase> testCases,
            RagEvaluationMode mode,
            int topK,
            Function<String, List<RagSource>> retriever
    ) {
        if (testCases == null || testCases.isEmpty()) {
            return RagBatchEvaluationResponse.builder()
                    .mode(mode)
                    .status(RagEvaluationStatus.COMPLETED)
                    .successful(true)
                    .totalQueries(0)
                    .positiveQueryCount(0)
                    .noAnswerQueryCount(0)
                    .noAnswerCorrectCount(0)
                    .noAnswerAccuracy(0.0)
                    .topK(topK)
                    .hitCount(0)
                    .hitRate(0.0)
                    .recallAtK(0.0)
                    .precisionAtK(0.0)
                    .mrr(0.0)
                    .averageLatencyMs(0)
                    .p50LatencyMs(0)
                    .p95LatencyMs(0)
                    .averageReturnedSources(0.0)
                    .details(List.of())
                    .build();
        }

        List<RagBatchEvaluationResponse.DetailResult> details = new ArrayList<>();
        int hitCount = 0;
        int positiveQueryCount = 0;
        int noAnswerQueryCount = 0;
        int noAnswerCorrectCount = 0;
        double sumReciprocalRank = 0.0;
        long totalLatencyMs = 0L;
        double sumRecall = 0.0;
        double sumPrecision = 0.0;
        int totalReturnedSources = 0;
        List<Long> latencies = new ArrayList<>();

        for (RagBatchEvaluationRequest.TestCase tc : testCases) {
            long start = System.nanoTime();
            List<RagSource> sources = retriever.apply(tc.getQuery());
            long latency = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
            totalLatencyMs += latency;
            totalReturnedSources += sources.size();
            latencies.add(latency);

            boolean expectedNoAnswer = Boolean.TRUE.equals(tc.getExpectedNoAnswer());
            if (expectedNoAnswer) {
                boolean noAnswerCorrect = sources.isEmpty();
                noAnswerQueryCount++;
                noAnswerCorrectCount += noAnswerCorrect ? 1 : 0;
                details.add(RagBatchEvaluationResponse.DetailResult.builder()
                        .query(tc.getQuery())
                        .hit(false)
                        .rank(0)
                        .reciprocalRank(0.0)
                        .expectedCount(0)
                        .matchedExpectedCount(0)
                        .recallAtK(0.0)
                        .precisionAtK(0.0)
                        .expectedNoAnswer(true)
                        .noAnswerCorrect(noAnswerCorrect)
                        .latencyMs(latency)
                        .expectedDocumentIds(List.of())
                        .expectedSourceKeys(List.of())
                        .expectedKeywords(List.of())
                        .retrievedSourceIds(sources.stream().map(RagSource::documentId).toList())
                        .retrievedSourceKeys(sources.stream().map(RagSource::sourceKey).toList())
                        .retrievedSources(sources.stream().map(RagSource::content).toList())
                        .build());
                continue;
            }
            positiveQueryCount++;

            Set<String> expectedDocuments = expectedValues(tc.getExpectedDocumentId(), tc.getExpectedDocumentIds());
            Set<String> expectedSourceKeys = expectedValues(
                    tc.getExpectedSourceKey(), tc.getExpectedSourceKeys());
            Set<String> expectedKeywords = expectedValues(tc.getExpectedKeyword(), tc.getExpectedKeywords());
            Set<String> matchedExpectations = new LinkedHashSet<>();
            int hitRank = 0;
            int relevantSourceCount = 0;
            boolean expectsIdentity = !expectedDocuments.isEmpty() || !expectedSourceKeys.isEmpty();
            boolean expectsKeywords = !expectedKeywords.isEmpty();

            for (int i = 0; i < sources.size(); i++) {
                RagSource s = sources.get(i);
                boolean identityMatched = false;
                if (expectedDocuments.contains(s.documentId())) {
                    matchedExpectations.add("doc:" + s.documentId());
                    identityMatched = true;
                }
                if (matchesIgnoreCase(expectedSourceKeys, s.sourceKey())) {
                    // expectedSourceKeys 是同一来源真值的可接受别名/替代来源，命中任一个即完成这一维度。
                    matchedExpectations.add("sourceKey");
                    identityMatched = true;
                }
                boolean keywordMatched = false;
                if (s.content() != null) {
                    for (String keyword : expectedKeywords) {
                        if (containsIgnoreCase(s.content(), keyword)) {
                            matchedExpectations.add("keyword:" + keyword);
                            keywordMatched = true;
                        }
                    }
                }
                boolean sourceRelevant = (!expectsIdentity || identityMatched)
                        && (!expectsKeywords || keywordMatched);
                if (sourceRelevant) {
                    relevantSourceCount++;
                    if (hitRank == 0) {
                        hitRank = i + 1;
                    }
                }
            }

            boolean hit = hitRank > 0;
            double rr = hit ? (1.0 / hitRank) : 0.0;
            int expectedCount = expectedDocuments.size()
                    + (expectedSourceKeys.isEmpty() ? 0 : 1)
                    + expectedKeywords.size();
            double recall = expectedCount == 0 ? 0.0 : (double) matchedExpectations.size() / expectedCount;
            double precision = (double) relevantSourceCount / topK;
            if (hit) hitCount++;
            sumReciprocalRank += rr;
            sumRecall += recall;
            sumPrecision += precision;

            details.add(RagBatchEvaluationResponse.DetailResult.builder()
                    .query(tc.getQuery())
                    .expectedDocumentId(tc.getExpectedDocumentId())
                    .expectedSourceKey(tc.getExpectedSourceKey())
                    .expectedKeyword(tc.getExpectedKeyword())
                    .hit(hit)
                    .rank(hitRank)
                    .reciprocalRank(rr)
                    .expectedCount(expectedCount)
                    .matchedExpectedCount(matchedExpectations.size())
                    .recallAtK(roundPercent(recall))
                    .precisionAtK(roundPercent(precision))
                    .expectedNoAnswer(false)
                    .noAnswerCorrect(null)
                    .latencyMs(latency)
                    .expectedDocumentIds(List.copyOf(expectedDocuments))
                    .expectedSourceKeys(List.copyOf(expectedSourceKeys))
                    .expectedKeywords(List.copyOf(expectedKeywords))
                    .retrievedSourceIds(sources.stream().map(RagSource::documentId).toList())
                    .retrievedSourceKeys(sources.stream().map(RagSource::sourceKey).toList())
                    .retrievedSources(sources.stream().map(RagSource::content).toList())
                    .build());
        }

        int total = testCases.size();
        double hitRate = positiveQueryCount > 0 ? (double) hitCount / positiveQueryCount : 0.0;
        double mrr = positiveQueryCount > 0 ? sumReciprocalRank / positiveQueryCount : 0.0;
        double avgLatency = total > 0 ? (double) totalLatencyMs / total : 0.0;
        double noAnswerAccuracy = noAnswerQueryCount > 0
                ? (double) noAnswerCorrectCount / noAnswerQueryCount : 0.0;

        return RagBatchEvaluationResponse.builder()
                .mode(mode)
                .status(RagEvaluationStatus.COMPLETED)
                .successful(true)
                .totalQueries(total)
                .positiveQueryCount(positiveQueryCount)
                .noAnswerQueryCount(noAnswerQueryCount)
                .noAnswerCorrectCount(noAnswerCorrectCount)
                .noAnswerAccuracy(roundPercent(noAnswerAccuracy))
                .topK(topK)
                .hitCount(hitCount)
                .hitRate(roundPercent(hitRate))
                .recallAtK(roundPercent(positiveQueryCount > 0 ? sumRecall / positiveQueryCount : 0.0))
                .precisionAtK(roundPercent(positiveQueryCount > 0 ? sumPrecision / positiveQueryCount : 0.0))
                .mrr(Math.round(mrr * 1000.0) / 1000.0)
                .averageLatencyMs(roundTwoDecimals(avgLatency))
                .p50LatencyMs(percentile(latencies, 0.50))
                .p95LatencyMs(percentile(latencies, 0.95))
                .averageReturnedSources(roundTwoDecimals((double) totalReturnedSources / total))
                .details(details)
                .build();
    }

    private int validateTopK(int topK) {
        if (topK < 1 || topK > MAX_EVALUATION_TOP_K) {
            throw new IllegalArgumentException("topK 必须在 1 到 " + MAX_EVALUATION_TOP_K + " 之间");
        }
        return topK;
    }

    private boolean containsIgnoreCase(String content, String keyword) {
        return content.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private boolean matchesIgnoreCase(Set<String> expected, String actual) {
        return actual != null && expected.stream().anyMatch(value -> value.equalsIgnoreCase(actual));
    }

    private String safeEvaluationError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "评测模式执行失败";
        }
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }

    private Set<String> expectedValues(String legacyValue, List<String> values) {
        Set<String> expected = new LinkedHashSet<>();
        if (legacyValue != null && !legacyValue.isBlank()) {
            expected.add(legacyValue);
        }
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(expected::add);
        }
        return expected;
    }

    static long percentile(List<Long> samples, double percentile) {
        if (samples == null || samples.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = samples.stream().sorted().toList();
        int rank = (int) Math.ceil(percentile * sorted.size());
        int index = Math.max(0, Math.min(rank - 1, sorted.size() - 1));
        return sorted.get(index);
    }

    private double roundPercent(double value) {
        return Math.round(value * 10_000.0) / 100.0;
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;

        public EmbeddingResponse() {}
        public EmbeddingResponse(float[] embedding) { this.embedding = embedding; }
        public float[] getEmbedding() { return embedding; }
        public void setEmbedding(float[] embedding) { this.embedding = embedding; }
    }

}
