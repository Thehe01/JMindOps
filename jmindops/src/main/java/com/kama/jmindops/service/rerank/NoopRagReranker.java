package com.kama.jmindops.service.rerank;

import com.kama.jmindops.service.RagSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** 默认 Provider：不部署重排模型时保持 RRF 结果，不伪造重排成绩。 */
@Component
@ConditionalOnProperty(prefix = "rag.reranker", name = "provider", havingValue = "none", matchIfMissing = true)
public class NoopRagReranker implements RagReranker {
    @Override
    public String providerId() {
        return "none";
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public List<RagSource> rerank(String query, List<RagSource> candidates, int topK) {
        throw new RerankerNotConfiguredException("Reranker 未配置；设置 rag.reranker.provider=http 或 tei 后才会执行重排");
    }
}
