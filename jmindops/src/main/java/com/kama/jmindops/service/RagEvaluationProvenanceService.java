package com.kama.jmindops.service;

import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.response.RagEvaluationComparisonResponse;
import com.kama.jmindops.service.rerank.RagReranker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagEvaluationProvenanceService {
    static final String RETRIEVAL_PIPELINE_VERSION = "hybrid-rrf-v4";
    static final String RETRIEVAL_POLICY = "vector=topK;hybridVector=rerank?candidateTopK:topK;"
            + "bm25=max(5,hybridVector);rrfK=60;chunkDedupe=stableChunkIdentity";
    private final DocumentMapper documentMapper;
    private final RagReranker reranker;
    private final DocumentIndexFingerprint indexFingerprint;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final String embeddingNormalization;
    private final String indexPipelineVersion;
    private final String rerankerModel;
    private final int answerTopK;
    private final int candidateTopK;
    private final boolean evidenceGateEnabled;
    private final double minimumRerankScore;
    private final int embeddingTimeoutSeconds;
    private final int rerankerTimeoutSeconds;

    public RagEvaluationProvenanceService(
            DocumentMapper documentMapper,
            RagReranker reranker,
            DocumentIndexFingerprint indexFingerprint,
            @Value("${rag.embedding.model}") String embeddingModel,
            @Value("${rag.embedding.dimensions:1024}") int embeddingDimensions,
            @Value("${rag.embedding.normalization:none}") String embeddingNormalization,
            @Value("${rag.index.pipeline-version:tika-flexmark-chunk-v1}") String indexPipelineVersion,
            @Value("${rag.reranker.model:BAAI/bge-reranker-v2-m3}") String rerankerModel,
            @Value("${rag.retrieval.answer-top-k:5}") int answerTopK,
            @Value("${rag.retrieval.candidate-top-k:20}") int candidateTopK,
            @Value("${rag.retrieval.evidence-gate-enabled:true}") boolean evidenceGateEnabled,
            @Value("${rag.retrieval.minimum-rerank-score:0.05}") double minimumRerankScore,
            @Value("${rag.embedding.request-timeout-seconds:30}") int embeddingTimeoutSeconds,
            @Value("${rag.reranker.request-timeout-seconds:30}") int rerankerTimeoutSeconds
    ) {
        this.documentMapper = documentMapper;
        this.reranker = reranker;
        this.indexFingerprint = indexFingerprint;
        this.embeddingModel = embeddingModel;
        this.embeddingDimensions = embeddingDimensions;
        this.embeddingNormalization = embeddingNormalization;
        this.indexPipelineVersion = indexPipelineVersion;
        this.rerankerModel = rerankerModel;
        this.answerTopK = answerTopK;
        this.candidateTopK = candidateTopK;
        this.evidenceGateEnabled = evidenceGateEnabled;
        this.minimumRerankScore = minimumRerankScore;
        this.embeddingTimeoutSeconds = embeddingTimeoutSeconds;
        this.rerankerTimeoutSeconds = rerankerTimeoutSeconds;
    }

    public RagEvaluationComparisonResponse attach(String kbId, RagEvaluationComparisonResponse response) {
        response.setEvaluationId(UUID.randomUUID().toString());
        response.setGeneratedAt(Instant.now().toString());
        response.setKnowledgeBaseId(kbId);
        response.setKnowledgeBaseSnapshot(snapshot(kbId));
        response.setEmbeddingModel(embeddingModel);
        response.setRerankerProvider(reranker.providerId());
        response.setRerankerModel(reranker.isConfigured() ? rerankerModel : null);
        response.setRetrievalPipelineVersion(RETRIEVAL_PIPELINE_VERSION);
        Map<String, Object> config = retrievalConfig();
        response.setRetrievalConfig(config);
        response.setRetrievalConfigHash(DocumentHashing.sha256(canonicalConfig(config)));
        return response;
    }

    public RagEvaluationComparisonResponse describe(String kbId) {
        return attach(kbId, RagEvaluationComparisonResponse.builder()
                .totalQueries(0)
                .topK(0)
                .results(List.of())
                .build());
    }

    private Map<String, Object> retrievalConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("embeddingModel", embeddingModel);
        config.put("embeddingDimensions", embeddingDimensions);
        config.put("embeddingNormalization", embeddingNormalization);
        config.put("embeddingTimeoutSeconds", embeddingTimeoutSeconds);
        config.put("indexPipelineVersion", indexPipelineVersion);
        config.put("indexFingerprint", indexFingerprint.current());
        config.put("rerankerProvider", reranker.providerId());
        config.put("rerankerModel", reranker.isConfigured() ? rerankerModel : "none");
        config.put("rerankerTimeoutSeconds", rerankerTimeoutSeconds);
        config.put("answerTopK", answerTopK);
        config.put("candidateTopK", candidateTopK);
        config.put("evidenceGateEnabled", evidenceGateEnabled);
        config.put("minimumRerankScore", minimumRerankScore);
        config.put("policy", RETRIEVAL_POLICY);
        return Map.copyOf(config);
    }

    private String canonicalConfig(Map<String, Object> config) {
        return config.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + ";" + right)
                .orElse("");
    }

    String snapshot(String kbId) {
        List<Document> documents = documentMapper.selectByKbId(kbId).stream()
                .sorted(Comparator.comparing(
                        document -> document.getSourceKey() == null ? document.getId() : document.getSourceKey()))
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (Document document : documents) {
            canonical.append(document.getId()).append('|')
                    .append(document.getSourceKey()).append('|')
                    .append(document.getContentHash()).append('|')
                    .append(document.getIndexFingerprint()).append('|')
                    .append(document.getIndexVersion()).append('|')
                    .append(document.getChunkCount()).append('|')
                    .append(document.getIndexStatus()).append('\n');
        }
        return DocumentHashing.sha256(canonical.toString());
    }
}
