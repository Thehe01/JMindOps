package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RagEvaluationComparisonResponse {
    private String evaluationId;
    private String generatedAt;
    private String knowledgeBaseId;
    private String knowledgeBaseSnapshot;
    private String embeddingModel;
    private String rerankerProvider;
    private String rerankerModel;
    private String retrievalPipelineVersion;
    private Map<String, Object> retrievalConfig;
    private String retrievalConfigHash;
    private String relevanceLabelType;
    private int totalQueries;
    private int topK;
    private List<RagBatchEvaluationResponse> results;

    public RagEvaluationComparisonResponse() {}

    public RagEvaluationComparisonResponse(
            String evaluationId,
            String generatedAt,
            String knowledgeBaseId,
            String knowledgeBaseSnapshot,
            String embeddingModel,
            String rerankerProvider,
            String rerankerModel,
            String retrievalPipelineVersion,
            Map<String, Object> retrievalConfig,
            String retrievalConfigHash,
            String relevanceLabelType,
            int totalQueries,
            int topK,
            List<RagBatchEvaluationResponse> results
    ) {
        this.evaluationId = evaluationId;
        this.generatedAt = generatedAt;
        this.knowledgeBaseId = knowledgeBaseId;
        this.knowledgeBaseSnapshot = knowledgeBaseSnapshot;
        this.embeddingModel = embeddingModel;
        this.rerankerProvider = rerankerProvider;
        this.rerankerModel = rerankerModel;
        this.retrievalPipelineVersion = retrievalPipelineVersion;
        this.retrievalConfig = retrievalConfig;
        this.retrievalConfigHash = retrievalConfigHash;
        this.relevanceLabelType = relevanceLabelType;
        this.totalQueries = totalQueries;
        this.topK = topK;
        this.results = results;
    }

    public String getEvaluationId() { return evaluationId; }
    public void setEvaluationId(String evaluationId) { this.evaluationId = evaluationId; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(String knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getKnowledgeBaseSnapshot() { return knowledgeBaseSnapshot; }
    public void setKnowledgeBaseSnapshot(String knowledgeBaseSnapshot) { this.knowledgeBaseSnapshot = knowledgeBaseSnapshot; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public String getRerankerProvider() { return rerankerProvider; }
    public void setRerankerProvider(String rerankerProvider) { this.rerankerProvider = rerankerProvider; }
    public String getRerankerModel() { return rerankerModel; }
    public void setRerankerModel(String rerankerModel) { this.rerankerModel = rerankerModel; }
    public String getRetrievalPipelineVersion() { return retrievalPipelineVersion; }
    public void setRetrievalPipelineVersion(String retrievalPipelineVersion) { this.retrievalPipelineVersion = retrievalPipelineVersion; }
    public Map<String, Object> getRetrievalConfig() { return retrievalConfig; }
    public void setRetrievalConfig(Map<String, Object> retrievalConfig) { this.retrievalConfig = retrievalConfig; }
    public String getRetrievalConfigHash() { return retrievalConfigHash; }
    public void setRetrievalConfigHash(String retrievalConfigHash) { this.retrievalConfigHash = retrievalConfigHash; }
    public String getRelevanceLabelType() { return relevanceLabelType; }
    public void setRelevanceLabelType(String relevanceLabelType) { this.relevanceLabelType = relevanceLabelType; }

    public int getTotalQueries() { return totalQueries; }
    public void setTotalQueries(int totalQueries) { this.totalQueries = totalQueries; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public List<RagBatchEvaluationResponse> getResults() { return results; }
    public void setResults(List<RagBatchEvaluationResponse> results) { this.results = results; }
}
