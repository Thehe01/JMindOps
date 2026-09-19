package com.kama.jmindops.service.rerank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kama.jmindops.service.RagSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OpenAI/Jina 风格 HTTP rerank 协议适配器，可由其他 Provider 实现直接替换。 */
@Component
@ConditionalOnProperty(prefix = "rag.reranker", name = "provider", havingValue = "http")
public class HttpRagReranker implements RagReranker {
    private final WebClient webClient;
    private final String endpoint;
    private final String model;
    private final int timeoutSeconds;

    public HttpRagReranker(
            WebClient.Builder builder,
            @Value("${rag.reranker.base-url:http://localhost:8001}") String baseUrl,
            @Value("${rag.reranker.endpoint:/v1/rerank}") String endpoint,
            @Value("${rag.reranker.model:BAAI/bge-reranker-v2-m3}") String model,
            @Value("${rag.reranker.request-timeout-seconds:30}") int timeoutSeconds
    ) {
        // vLLM is served by Uvicorn, which does not support the clear-text h2c upgrade
        // attempted by Java's HTTP/2-preferred client. Pinning this provider to HTTP/1.1
        // prevents Uvicorn from discarding the request body and returning HTTP 400.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.webClient = builder
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
        this.endpoint = endpoint;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String providerId() {
        return "http";
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
        RerankResponse response = webClient.post()
                .uri(endpoint)
                .bodyValue(Map.of(
                        "model", model,
                        "query", query,
                        "documents", candidates.stream().map(RagSource::content).toList(),
                        "top_n", Math.min(topK, candidates.size())
                ))
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .block(Duration.ofSeconds(timeoutSeconds));

        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new IllegalStateException("Reranker 返回为空");
        }
        Set<Integer> seen = new HashSet<>();
        List<RagSource> ranked = response.results().stream()
                .sorted((left, right) -> Double.compare(right.relevanceScore(), left.relevanceScore()))
                .filter(result -> result.index() >= 0 && result.index() < candidates.size())
                .filter(result -> seen.add(result.index()))
                .limit(topK)
                .map(result -> {
                    RagSource candidate = candidates.get(result.index());
                    return new RagSource(
                            candidate.documentId(), candidate.sourceKey(), candidate.content(), result.relevanceScore());
                })
                .toList();
        if (ranked.isEmpty()) {
            throw new IllegalStateException("Reranker 未返回合法候选索引");
        }
        return ranked;
    }

    public record RerankResponse(List<RerankResult> results) {}

    public record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {}
}
