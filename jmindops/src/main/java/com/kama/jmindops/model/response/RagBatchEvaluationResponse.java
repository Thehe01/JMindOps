package com.kama.jmindops.model.response;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class RagBatchEvaluationResponse {
    private int totalQueries;
    private int hitCount;
    private double hitRate;
    private double mrr;
    private double averageLatencyMs;
    private List<DetailResult> details;

    public RagBatchEvaluationResponse() {}
    public RagBatchEvaluationResponse(int totalQueries, int hitCount, double hitRate, double mrr, double averageLatencyMs, List<DetailResult> details) {
        this.totalQueries = totalQueries;
        this.hitCount = hitCount;
        this.hitRate = hitRate;
        this.mrr = mrr;
        this.averageLatencyMs = averageLatencyMs;
        this.details = details;
    }

    public int getTotalQueries() { return totalQueries; }
    public void setTotalQueries(int totalQueries) { this.totalQueries = totalQueries; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public double getHitRate() { return hitRate; }
    public void setHitRate(double hitRate) { this.hitRate = hitRate; }
    public double getMrr() { return mrr; }
    public void setMrr(double mrr) { this.mrr = mrr; }
    public double getAverageLatencyMs() { return averageLatencyMs; }
    public void setAverageLatencyMs(double averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; }
    public List<DetailResult> getDetails() { return details; }
    public void setDetails(List<DetailResult> details) { this.details = details; }

    public static RagBatchEvaluationResponseBuilder builder() { return new RagBatchEvaluationResponseBuilder(); }
    public static class RagBatchEvaluationResponseBuilder {
        private int totalQueries;
        private int hitCount;
        private double hitRate;
        private double mrr;
        private double averageLatencyMs;
        private List<DetailResult> details;

        public RagBatchEvaluationResponseBuilder() {}
        public RagBatchEvaluationResponseBuilder totalQueries(int totalQueries) { this.totalQueries = totalQueries; return this; }
        public RagBatchEvaluationResponseBuilder hitCount(int hitCount) { this.hitCount = hitCount; return this; }
        public RagBatchEvaluationResponseBuilder hitRate(double hitRate) { this.hitRate = hitRate; return this; }
        public RagBatchEvaluationResponseBuilder mrr(double mrr) { this.mrr = mrr; return this; }
        public RagBatchEvaluationResponseBuilder averageLatencyMs(double averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; return this; }
        public RagBatchEvaluationResponseBuilder details(List<DetailResult> details) { this.details = details; return this; }
        public RagBatchEvaluationResponse build() { return new RagBatchEvaluationResponse(totalQueries, hitCount, hitRate, mrr, averageLatencyMs, details); }
    }

    @Data
    @Builder
    public static class DetailResult {
        private String query;
        private boolean hit;
        private int rank;
        private double reciprocalRank;
        private long latencyMs;
        private String expectedDocumentId;
        private String expectedKeyword;
        private List<String> retrievedSources;

        public DetailResult() {}
        public DetailResult(String query, boolean hit, int rank, double reciprocalRank, long latencyMs, String expectedDocumentId, String expectedKeyword, List<String> retrievedSources) {
            this.query = query;
            this.hit = hit;
            this.rank = rank;
            this.reciprocalRank = reciprocalRank;
            this.latencyMs = latencyMs;
            this.expectedDocumentId = expectedDocumentId;
            this.expectedKeyword = expectedKeyword;
            this.retrievedSources = retrievedSources;
        }

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public boolean isHit() { return hit; }
        public void setHit(boolean hit) { this.hit = hit; }
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        public double getReciprocalRank() { return reciprocalRank; }
        public void setReciprocalRank(double reciprocalRank) { this.reciprocalRank = reciprocalRank; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getExpectedDocumentId() { return expectedDocumentId; }
        public void setExpectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; }
        public String getExpectedKeyword() { return expectedKeyword; }
        public void setExpectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; }
        public List<String> getRetrievedSources() { return retrievedSources; }
        public void setRetrievedSources(List<String> retrievedSources) { this.retrievedSources = retrievedSources; }

        public static DetailResultBuilder builder() { return new DetailResultBuilder(); }
        public static class DetailResultBuilder {
            private String query;
            private boolean hit;
            private int rank;
            private double reciprocalRank;
            private long latencyMs;
            private String expectedDocumentId;
            private String expectedKeyword;
            private List<String> retrievedSources;

            public DetailResultBuilder() {}
            public DetailResultBuilder query(String query) { this.query = query; return this; }
            public DetailResultBuilder hit(boolean hit) { this.hit = hit; return this; }
            public DetailResultBuilder rank(int rank) { this.rank = rank; return this; }
            public DetailResultBuilder reciprocalRank(double reciprocalRank) { this.reciprocalRank = reciprocalRank; return this; }
            public DetailResultBuilder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
            public DetailResultBuilder expectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; return this; }
            public DetailResultBuilder expectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; return this; }
            public DetailResultBuilder retrievedSources(List<String> retrievedSources) { this.retrievedSources = retrievedSources; return this; }
            public DetailResult build() { return new DetailResult(query, hit, rank, reciprocalRank, latencyMs, expectedDocumentId, expectedKeyword, retrievedSources); }
        }
    }
}
