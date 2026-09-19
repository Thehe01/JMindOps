package com.kama.jmindops.model.request;

/**
 * 检索评测模式。显式区分模式，避免把 Reranker 失败后的 RRF 降级结果误记为重排结果。
 */
public enum RagEvaluationMode {
    VECTOR,
    HYBRID_RRF,
    HYBRID_RERANK
}
