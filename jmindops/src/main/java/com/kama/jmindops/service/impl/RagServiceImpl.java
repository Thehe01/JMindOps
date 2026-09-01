package com.kama.jmindops.service.impl;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.request.RagBatchEvaluationRequest;
import com.kama.jmindops.model.response.RagBatchEvaluationResponse;
import com.kama.jmindops.service.RagService;
import com.kama.jmindops.service.RagSource;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final String embeddingModel;
    private final int requestTimeoutSeconds;
    private final boolean rerankerEnabled;
    private final String rerankerModel;

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            @Value("${rag.embedding.base-url}") String embeddingBaseUrl,
            @Value("${rag.embedding.model}") String embeddingModel,
            @Value("${rag.embedding.request-timeout-seconds}") int requestTimeoutSeconds,
            @Value("${rag.reranker.enabled}") boolean rerankerEnabled,
            @Value("${rag.reranker.model}") String rerankerModel
    ) {
        this.webClient = builder.baseUrl(embeddingBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.embeddingModel = embeddingModel;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.rerankerEnabled = rerankerEnabled;
        this.rerankerModel = rerankerModel;
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
        String queryEmbedding = toPgVector(doEmbed(title));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<String> hybridSearch(String kbId, String title) {
        return hybridSearchWithSources(kbId, title).stream().map(RagSource::content).toList();
    }

    @Override
    public List<RagSource> hybridSearchWithSources(String kbId, String query) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<ChunkBgeM3> vectorChunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
        List<ChunkBgeM3> keywordChunks = chunkBgeM3Mapper.keywordSearch(kbId, query, 5);

        Map<String, ChunkBgeM3> uniqueChunks = new LinkedHashMap<>();
        vectorChunks.forEach(chunk -> uniqueChunks.putIfAbsent(chunk.getContent(), chunk));
        keywordChunks.forEach(chunk -> uniqueChunks.putIfAbsent(chunk.getContent(), chunk));

        List<String> orderedContents = reciprocalRankFusion(vectorChunks, keywordChunks);
        if (orderedContents.size() > 3 && rerankerEnabled) {
            try {
                orderedContents = doRerank(query, new ArrayList<>(uniqueChunks.keySet()), 3);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(RagServiceImpl.class).warn("Reranker 接口调用失败，触发降级策略: {}", e.getMessage());
            }
        }

        return orderedContents.stream()
                .limit(3)
                .map(uniqueChunks::get)
                .map(chunk -> new RagSource(chunk.getDocId(), chunk.getContent()))
                .toList();
    }

    /**
     * 使用 Reciprocal Rank Fusion 融合向量与关键词排名。
     * 这也是 reranker 关闭/失败时的确定性降级路径，避免某一路候选因插入顺序被全部截断。
     */
    private List<String> reciprocalRankFusion(
            List<ChunkBgeM3> vectorChunks,
            List<ChunkBgeM3> keywordChunks
    ) {
        final int rankConstant = 60;
        Map<String, Double> scores = new HashMap<>();
        addRankScores(scores, vectorChunks, rankConstant);
        addRankScores(scores, keywordChunks, rankConstant);

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
            String content = rankedChunks.get(index).getContent();
            scores.merge(content, 1.0 / (rankConstant + index + 1), Double::sum);
        }
    }

    /**
     * 调用本地 Reranker 模型进行重排序 (假设服务运行在本地 11434 端口的 /v1/rerank 接口)
     */
    private List<String> doRerank(String query, List<String> documents, int topK) {
        try {
            RerankResponse resp = webClient.post()
                    .uri("/v1/rerank")
                    .bodyValue(Map.of(
                            "model", rerankerModel,
                            "query", query,
                            "documents", documents,
                            "top_n", topK
                    ))
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block(Duration.ofSeconds(requestTimeoutSeconds));

            if (resp != null && resp.getResults() != null) {
                return resp.getResults().stream()
                        .sorted((a, b) -> Float.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                        .map(r -> documents.get(r.getIndex()))
                        .toList();
            }
        } catch (Exception e) {
            throw new RuntimeException("Reranker 请求失败", e);
        }
        return documents.stream().limit(topK).toList();
    }

    @Override
    public RagBatchEvaluationResponse evaluateBatch(String kbId, List<RagBatchEvaluationRequest.TestCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return RagBatchEvaluationResponse.builder()
                    .totalQueries(0)
                    .hitCount(0)
                    .hitRate(0.0)
                    .mrr(0.0)
                    .averageLatencyMs(0)
                    .details(List.of())
                    .build();
        }

        List<RagBatchEvaluationResponse.DetailResult> details = new ArrayList<>();
        int hitCount = 0;
        double sumReciprocalRank = 0.0;
        long totalLatencyMs = 0L;

        for (RagBatchEvaluationRequest.TestCase tc : testCases) {
            long start = System.currentTimeMillis();
            List<RagSource> sources = hybridSearchWithSources(kbId, tc.getQuery());
            long latency = System.currentTimeMillis() - start;
            totalLatencyMs += latency;

            boolean hit = false;
            int hitRank = 0;

            for (int i = 0; i < sources.size(); i++) {
                RagSource s = sources.get(i);
                boolean docMatches = tc.getExpectedDocumentId() != null && tc.getExpectedDocumentId().equals(s.documentId());
                boolean keywordMatches = tc.getExpectedKeyword() != null && s.content() != null && s.content().contains(tc.getExpectedKeyword());

                if (docMatches || keywordMatches) {
                    hit = true;
                    hitRank = i + 1;
                    break;
                }
            }

            double rr = hit ? (1.0 / hitRank) : 0.0;
            if (hit) hitCount++;
            sumReciprocalRank += rr;

            details.add(RagBatchEvaluationResponse.DetailResult.builder()
                    .query(tc.getQuery())
                    .expectedDocumentId(tc.getExpectedDocumentId())
                    .expectedKeyword(tc.getExpectedKeyword())
                    .hit(hit)
                    .rank(hitRank)
                    .reciprocalRank(rr)
                    .latencyMs(latency)
                    .retrievedSources(sources.stream().map(RagSource::content).toList())
                    .build());
        }

        int total = testCases.size();
        double hitRate = total > 0 ? (double) hitCount / total : 0.0;
        double mrr = total > 0 ? sumReciprocalRank / total : 0.0;
        long avgLatency = total > 0 ? totalLatencyMs / total : 0;

        return RagBatchEvaluationResponse.builder()
                .totalQueries(total)
                .hitCount(hitCount)
                .hitRate(Math.round(hitRate * 10000.0) / 100.0)
                .mrr(Math.round(mrr * 1000.0) / 1000.0)
                .averageLatencyMs(avgLatency)
                .details(details)
                .build();
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

    @Data
    private static class RerankResponse {
        private List<RerankResult> results;

        public RerankResponse() {}
        public RerankResponse(List<RerankResult> results) { this.results = results; }
        public List<RerankResult> getResults() { return results; }
        public void setResults(List<RerankResult> results) { this.results = results; }
    }

    @Data
    private static class RerankResult {
        private int index;
        @com.fasterxml.jackson.annotation.JsonProperty("relevance_score")
        private float relevanceScore;

        public RerankResult() {}
        public RerankResult(int index, float relevanceScore) { this.index = index; this.relevanceScore = relevanceScore; }
        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public float getRelevanceScore() { return relevanceScore; }
        public void setRelevanceScore(float relevanceScore) { this.relevanceScore = relevanceScore; }
    }
}
