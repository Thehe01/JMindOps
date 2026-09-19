package com.kama.jmindops.service.rerank;

import com.kama.jmindops.service.RagSource;

import java.util.List;

/**
 * RAG 重排扩展点。核心检索链路只依赖该接口，不绑定具体模型或服务协议。
 */
public interface RagReranker {
    String providerId();

    boolean isConfigured();

    List<RagSource> rerank(String query, List<RagSource> candidates, int topK);
}
