package com.kama.jmindops.service.rerank;

import com.kama.jmindops.service.RagSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hugging Face Text Embeddings Inference（TEI）/rerank 协议适配器。 */
@Component
@ConditionalOnProperty(prefix = "rag.reranker", name = "provider", havingValue = "tei")
public class TeiRagReranker implements RagReranker {
    private final WebClient webClient;
    private final String endpoint;
    private final int timeoutSeconds;

    public TeiRagReranker(
            WebClient.Builder builder,
            @Value("${rag.reranker.base-url:http://localhost:8001}") String baseUrl,
            @Value("${rag.reranker.tei-endpoint:/rerank}") String endpoint,
            @Value("${rag.reranker.request-timeout-seconds:30}") int timeoutSeconds
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.endpoint = endpoint;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerId() {
        return "tei";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public List<RagSource> rerank(String query, List<RagSource> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<TeiRank> response = webClient.post()
                .uri(endpoint)
                .bodyValue(Map.of(
                        "query", query,
                        "texts", candidates.stream().map(RagSource::content).toList(),
                        "raw_scores", false,
                        "return_text", false,
                        "truncate", true
                ))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<TeiRank>>() {})
                .block(Duration.ofSeconds(timeoutSeconds));

        if (response == null || response.isEmpty()) {
            throw new IllegalStateException("TEI Reranker 返回为空");
        }
        Set<Integer> seen = new HashSet<>();
        List<RagSource> ranked = response.stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .filter(result -> result.index() >= 0 && result.index() < candidates.size())
                .filter(result -> seen.add(result.index()))
                .limit(Math.min(topK, candidates.size()))
                .map(result -> {
                    RagSource candidate = candidates.get(result.index());
                    return new RagSource(
                            candidate.documentId(), candidate.sourceKey(), candidate.content(), result.score());
                })
                .toList();
        if (ranked.isEmpty()) {
            throw new IllegalStateException("TEI Reranker 未返回合法候选索引");
        }
        return ranked;
    }

    public record TeiRank(int index, double score) {}
}
