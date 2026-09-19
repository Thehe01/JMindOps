package com.kama.jmindops.model.response;

import com.kama.jmindops.model.request.RagEvaluationMode;
import com.kama.jmindops.model.request.RagEvaluationStatus;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class RagBatchEvaluationResponse {
    private RagEvaluationMode mode;
    private RagEvaluationStatus status;
    private boolean successful;
    private String error;
    private int totalQueries;
    private int positiveQueryCount;
    private int noAnswerQueryCount;
    private int noAnswerCorrectCount;
    private double noAnswerAccuracy;
    private int topK;
    private int hitCount;
    private double hitRate;
    private double recallAtK;
    private double precisionAtK;
    private double mrr;
    private double averageLatencyMs;
    private long p50LatencyMs;
    private long p95LatencyMs;
    private double averageReturnedSources;
    private List<DetailResult> details;

    public RagBatchEvaluationResponse() {}
    public RagBatchEvaluationResponse(RagEvaluationMode mode, RagEvaluationStatus status, boolean successful, String error, int totalQueries, int positiveQueryCount, int noAnswerQueryCount, int noAnswerCorrectCount, double noAnswerAccuracy, int topK, int hitCount, double hitRate, double recallAtK, double precisionAtK, double mrr, double averageLatencyMs, long p50LatencyMs, long p95LatencyMs, double averageReturnedSources, List<DetailResult> details) {
        this.mode = mode;
        this.status = status;
        this.successful = successful;
        this.error = error;
        this.totalQueries = totalQueries;
        this.positiveQueryCount = positiveQueryCount;
        this.noAnswerQueryCount = noAnswerQueryCount;
        this.noAnswerCorrectCount = noAnswerCorrectCount;
        this.noAnswerAccuracy = noAnswerAccuracy;
        this.topK = topK;
        this.hitCount = hitCount;
        this.hitRate = hitRate;
        this.recallAtK = recallAtK;
        this.precisionAtK = precisionAtK;
        this.mrr = mrr;
        this.averageLatencyMs = averageLatencyMs;
        this.p50LatencyMs = p50LatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.averageReturnedSources = averageReturnedSources;
        this.details = details;
    }

    public RagEvaluationMode getMode() { return mode; }
    public void setMode(RagEvaluationMode mode) { this.mode = mode; }
    public RagEvaluationStatus getStatus() { return status; }
    public void setStatus(RagEvaluationStatus status) { this.status = status; }
    public boolean isSuccessful() { return successful; }
    public void setSuccessful(boolean successful) { this.successful = successful; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public int getTotalQueries() { return totalQueries; }
    public void setTotalQueries(int totalQueries) { this.totalQueries = totalQueries; }
    public int getPositiveQueryCount() { return positiveQueryCount; }
    public void setPositiveQueryCount(int positiveQueryCount) { this.positiveQueryCount = positiveQueryCount; }
    public int getNoAnswerQueryCount() { return noAnswerQueryCount; }
    public void setNoAnswerQueryCount(int noAnswerQueryCount) { this.noAnswerQueryCount = noAnswerQueryCount; }
    public int getNoAnswerCorrectCount() { return noAnswerCorrectCount; }
    public void setNoAnswerCorrectCount(int noAnswerCorrectCount) { this.noAnswerCorrectCount = noAnswerCorrectCount; }
    public double getNoAnswerAccuracy() { return noAnswerAccuracy; }
    public void setNoAnswerAccuracy(double noAnswerAccuracy) { this.noAnswerAccuracy = noAnswerAccuracy; }
    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }
    public int getHitCount() { return hitCount; }
    public void setHitCount(int hitCount) { this.hitCount = hitCount; }
    public double getHitRate() { return hitRate; }
    public void setHitRate(double hitRate) { this.hitRate = hitRate; }
    public double getRecallAtK() { return recallAtK; }
    public void setRecallAtK(double recallAtK) { this.recallAtK = recallAtK; }
    public double getPrecisionAtK() { return precisionAtK; }
    public void setPrecisionAtK(double precisionAtK) { this.precisionAtK = precisionAtK; }
    public double getMrr() { return mrr; }
    public void setMrr(double mrr) { this.mrr = mrr; }
    public double getAverageLatencyMs() { return averageLatencyMs; }
    public void setAverageLatencyMs(double averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; }
    public long getP50LatencyMs() { return p50LatencyMs; }
    public void setP50LatencyMs(long p50LatencyMs) { this.p50LatencyMs = p50LatencyMs; }
    public long getP95LatencyMs() { return p95LatencyMs; }
    public void setP95LatencyMs(long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; }
    public double getAverageReturnedSources() { return averageReturnedSources; }
    public void setAverageReturnedSources(double averageReturnedSources) { this.averageReturnedSources = averageReturnedSources; }
    public List<DetailResult> getDetails() { return details; }
    public void setDetails(List<DetailResult> details) { this.details = details; }

    public static RagBatchEvaluationResponseBuilder builder() { return new RagBatchEvaluationResponseBuilder(); }
    public static class RagBatchEvaluationResponseBuilder {
        private RagEvaluationMode mode;
        private RagEvaluationStatus status;
        private boolean successful;
        private String error;
        private int totalQueries;
        private int positiveQueryCount;
        private int noAnswerQueryCount;
        private int noAnswerCorrectCount;
        private double noAnswerAccuracy;
        private int topK;
        private int hitCount;
        private double hitRate;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double averageLatencyMs;
        private long p50LatencyMs;
        private long p95LatencyMs;
        private double averageReturnedSources;
        private List<DetailResult> details;

        public RagBatchEvaluationResponseBuilder() {}
        public RagBatchEvaluationResponseBuilder mode(RagEvaluationMode mode) { this.mode = mode; return this; }
        public RagBatchEvaluationResponseBuilder status(RagEvaluationStatus status) { this.status = status; return this; }
        public RagBatchEvaluationResponseBuilder successful(boolean successful) { this.successful = successful; return this; }
        public RagBatchEvaluationResponseBuilder error(String error) { this.error = error; return this; }
        public RagBatchEvaluationResponseBuilder totalQueries(int totalQueries) { this.totalQueries = totalQueries; return this; }
        public RagBatchEvaluationResponseBuilder positiveQueryCount(int positiveQueryCount) { this.positiveQueryCount = positiveQueryCount; return this; }
        public RagBatchEvaluationResponseBuilder noAnswerQueryCount(int noAnswerQueryCount) { this.noAnswerQueryCount = noAnswerQueryCount; return this; }
        public RagBatchEvaluationResponseBuilder noAnswerCorrectCount(int noAnswerCorrectCount) { this.noAnswerCorrectCount = noAnswerCorrectCount; return this; }
        public RagBatchEvaluationResponseBuilder noAnswerAccuracy(double noAnswerAccuracy) { this.noAnswerAccuracy = noAnswerAccuracy; return this; }
        public RagBatchEvaluationResponseBuilder topK(int topK) { this.topK = topK; return this; }
        public RagBatchEvaluationResponseBuilder hitCount(int hitCount) { this.hitCount = hitCount; return this; }
        public RagBatchEvaluationResponseBuilder hitRate(double hitRate) { this.hitRate = hitRate; return this; }
        public RagBatchEvaluationResponseBuilder recallAtK(double recallAtK) { this.recallAtK = recallAtK; return this; }
        public RagBatchEvaluationResponseBuilder precisionAtK(double precisionAtK) { this.precisionAtK = precisionAtK; return this; }
        public RagBatchEvaluationResponseBuilder mrr(double mrr) { this.mrr = mrr; return this; }
        public RagBatchEvaluationResponseBuilder averageLatencyMs(double averageLatencyMs) { this.averageLatencyMs = averageLatencyMs; return this; }
        public RagBatchEvaluationResponseBuilder p50LatencyMs(long p50LatencyMs) { this.p50LatencyMs = p50LatencyMs; return this; }
        public RagBatchEvaluationResponseBuilder p95LatencyMs(long p95LatencyMs) { this.p95LatencyMs = p95LatencyMs; return this; }
        public RagBatchEvaluationResponseBuilder averageReturnedSources(double averageReturnedSources) { this.averageReturnedSources = averageReturnedSources; return this; }
        public RagBatchEvaluationResponseBuilder details(List<DetailResult> details) { this.details = details; return this; }
        public RagBatchEvaluationResponse build() { return new RagBatchEvaluationResponse(mode, status, successful, error, totalQueries, positiveQueryCount, noAnswerQueryCount, noAnswerCorrectCount, noAnswerAccuracy, topK, hitCount, hitRate, recallAtK, precisionAtK, mrr, averageLatencyMs, p50LatencyMs, p95LatencyMs, averageReturnedSources, details); }
    }

    @Data
    @Builder
    public static class DetailResult {
        private String query;
        private boolean hit;
        private int rank;
        private double reciprocalRank;
        private int expectedCount;
        private int matchedExpectedCount;
        private double recallAtK;
        private double precisionAtK;
        private boolean expectedNoAnswer;
        private Boolean noAnswerCorrect;
        private long latencyMs;
        private String expectedDocumentId;
        private String expectedSourceKey;
        private String expectedKeyword;
        private List<String> expectedDocumentIds;
        private List<String> expectedSourceKeys;
        private List<String> expectedKeywords;
        private List<String> retrievedSourceIds;
        private List<String> retrievedSourceKeys;
        private List<String> retrievedSources;

        public DetailResult() {}
        public DetailResult(String query, boolean hit, int rank, double reciprocalRank, int expectedCount, int matchedExpectedCount, double recallAtK, double precisionAtK, boolean expectedNoAnswer, Boolean noAnswerCorrect, long latencyMs, String expectedDocumentId, String expectedSourceKey, String expectedKeyword, List<String> expectedDocumentIds, List<String> expectedSourceKeys, List<String> expectedKeywords, List<String> retrievedSourceIds, List<String> retrievedSourceKeys, List<String> retrievedSources) {
            this.query = query;
            this.hit = hit;
            this.rank = rank;
            this.reciprocalRank = reciprocalRank;
            this.expectedCount = expectedCount;
            this.matchedExpectedCount = matchedExpectedCount;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.expectedNoAnswer = expectedNoAnswer;
            this.noAnswerCorrect = noAnswerCorrect;
            this.latencyMs = latencyMs;
            this.expectedDocumentId = expectedDocumentId;
            this.expectedSourceKey = expectedSourceKey;
            this.expectedKeyword = expectedKeyword;
            this.expectedDocumentIds = expectedDocumentIds;
            this.expectedSourceKeys = expectedSourceKeys;
            this.expectedKeywords = expectedKeywords;
            this.retrievedSourceIds = retrievedSourceIds;
            this.retrievedSourceKeys = retrievedSourceKeys;
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
        public int getExpectedCount() { return expectedCount; }
        public void setExpectedCount(int expectedCount) { this.expectedCount = expectedCount; }
        public int getMatchedExpectedCount() { return matchedExpectedCount; }
        public void setMatchedExpectedCount(int matchedExpectedCount) { this.matchedExpectedCount = matchedExpectedCount; }
        public double getRecallAtK() { return recallAtK; }
        public void setRecallAtK(double recallAtK) { this.recallAtK = recallAtK; }
        public double getPrecisionAtK() { return precisionAtK; }
        public void setPrecisionAtK(double precisionAtK) { this.precisionAtK = precisionAtK; }
        public boolean isExpectedNoAnswer() { return expectedNoAnswer; }
        public void setExpectedNoAnswer(boolean expectedNoAnswer) { this.expectedNoAnswer = expectedNoAnswer; }
        public Boolean getNoAnswerCorrect() { return noAnswerCorrect; }
        public void setNoAnswerCorrect(Boolean noAnswerCorrect) { this.noAnswerCorrect = noAnswerCorrect; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getExpectedDocumentId() { return expectedDocumentId; }
        public void setExpectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; }
        public String getExpectedSourceKey() { return expectedSourceKey; }
        public void setExpectedSourceKey(String expectedSourceKey) { this.expectedSourceKey = expectedSourceKey; }
        public String getExpectedKeyword() { return expectedKeyword; }
        public void setExpectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; }
        public List<String> getExpectedDocumentIds() { return expectedDocumentIds; }
        public void setExpectedDocumentIds(List<String> expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; }
        public List<String> getExpectedSourceKeys() { return expectedSourceKeys; }
        public void setExpectedSourceKeys(List<String> expectedSourceKeys) { this.expectedSourceKeys = expectedSourceKeys; }
        public List<String> getExpectedKeywords() { return expectedKeywords; }
        public void setExpectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; }
        public List<String> getRetrievedSourceIds() { return retrievedSourceIds; }
        public void setRetrievedSourceIds(List<String> retrievedSourceIds) { this.retrievedSourceIds = retrievedSourceIds; }
        public List<String> getRetrievedSourceKeys() { return retrievedSourceKeys; }
        public void setRetrievedSourceKeys(List<String> retrievedSourceKeys) { this.retrievedSourceKeys = retrievedSourceKeys; }
        public List<String> getRetrievedSources() { return retrievedSources; }
        public void setRetrievedSources(List<String> retrievedSources) { this.retrievedSources = retrievedSources; }

        public static DetailResultBuilder builder() { return new DetailResultBuilder(); }
        public static class DetailResultBuilder {
            private String query;
            private boolean hit;
            private int rank;
            private double reciprocalRank;
            private int expectedCount;
            private int matchedExpectedCount;
            private double recallAtK;
            private double precisionAtK;
            private boolean expectedNoAnswer;
            private Boolean noAnswerCorrect;
            private long latencyMs;
            private String expectedDocumentId;
            private String expectedSourceKey;
            private String expectedKeyword;
            private List<String> expectedDocumentIds;
            private List<String> expectedSourceKeys;
            private List<String> expectedKeywords;
            private List<String> retrievedSourceIds;
            private List<String> retrievedSourceKeys;
            private List<String> retrievedSources;

            public DetailResultBuilder() {}
            public DetailResultBuilder query(String query) { this.query = query; return this; }
            public DetailResultBuilder hit(boolean hit) { this.hit = hit; return this; }
            public DetailResultBuilder rank(int rank) { this.rank = rank; return this; }
            public DetailResultBuilder reciprocalRank(double reciprocalRank) { this.reciprocalRank = reciprocalRank; return this; }
            public DetailResultBuilder expectedCount(int expectedCount) { this.expectedCount = expectedCount; return this; }
            public DetailResultBuilder matchedExpectedCount(int matchedExpectedCount) { this.matchedExpectedCount = matchedExpectedCount; return this; }
            public DetailResultBuilder recallAtK(double recallAtK) { this.recallAtK = recallAtK; return this; }
            public DetailResultBuilder precisionAtK(double precisionAtK) { this.precisionAtK = precisionAtK; return this; }
            public DetailResultBuilder expectedNoAnswer(boolean expectedNoAnswer) { this.expectedNoAnswer = expectedNoAnswer; return this; }
            public DetailResultBuilder noAnswerCorrect(Boolean noAnswerCorrect) { this.noAnswerCorrect = noAnswerCorrect; return this; }
            public DetailResultBuilder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
            public DetailResultBuilder expectedDocumentId(String expectedDocumentId) { this.expectedDocumentId = expectedDocumentId; return this; }
            public DetailResultBuilder expectedSourceKey(String expectedSourceKey) { this.expectedSourceKey = expectedSourceKey; return this; }
            public DetailResultBuilder expectedKeyword(String expectedKeyword) { this.expectedKeyword = expectedKeyword; return this; }
            public DetailResultBuilder expectedDocumentIds(List<String> expectedDocumentIds) { this.expectedDocumentIds = expectedDocumentIds; return this; }
            public DetailResultBuilder expectedSourceKeys(List<String> expectedSourceKeys) { this.expectedSourceKeys = expectedSourceKeys; return this; }
            public DetailResultBuilder expectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; return this; }
            public DetailResultBuilder retrievedSourceIds(List<String> retrievedSourceIds) { this.retrievedSourceIds = retrievedSourceIds; return this; }
            public DetailResultBuilder retrievedSourceKeys(List<String> retrievedSourceKeys) { this.retrievedSourceKeys = retrievedSourceKeys; return this; }
            public DetailResultBuilder retrievedSources(List<String> retrievedSources) { this.retrievedSources = retrievedSources; return this; }
            public DetailResult build() { return new DetailResult(query, hit, rank, reciprocalRank, expectedCount, matchedExpectedCount, recallAtK, precisionAtK, expectedNoAnswer, noAnswerCorrect, latencyMs, expectedDocumentId, expectedSourceKey, expectedKeyword, expectedDocumentIds, expectedSourceKeys, expectedKeywords, retrievedSourceIds, retrievedSourceKeys, retrievedSources); }
        }
    }
}
