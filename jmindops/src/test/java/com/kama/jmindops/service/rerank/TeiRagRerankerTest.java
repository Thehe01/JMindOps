package com.kama.jmindops.service.rerank;

import com.kama.jmindops.service.RagSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeiRagRerankerTest {

    @Test
    void consumesTeiResponseAndPreservesSourceMapping() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            capturedRequest.set(request);
            return Mono.just(jsonResponse("""
                    [
                      {"index":2,"score":0.93},
                      {"index":0,"score":0.72},
                      {"index":1,"score":0.11}
                    ]
                    """));
        });
        TeiRagReranker reranker = new TeiRagReranker(
                builder,
                "http://localhost:8001",
                "/rerank",
                5
        );
        List<RagSource> candidates = List.of(
                new RagSource("doc-0", "first"),
                new RagSource("doc-1", "second"),
                new RagSource("doc-2", "third")
        );

        List<RagSource> ranked = reranker.rerank("query", candidates, 2);

        assertThat(ranked).extracting(RagSource::documentId)
                .containsExactly("doc-2", "doc-0");
        assertThat(ranked).extracting(RagSource::relevanceScore)
                .containsExactly(0.93, 0.72);
        assertThat(capturedRequest.get().url().toString())
                .isEqualTo("http://localhost:8001/rerank");
    }

    @Test
    void rejectsResponseWithoutAnyValidCandidateIndex() {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request ->
                Mono.just(jsonResponse("[{\"index\":99,\"score\":0.99}]")));
        TeiRagReranker reranker = new TeiRagReranker(
                builder,
                "http://localhost:8001",
                "/rerank",
                5
        );

        assertThatThrownBy(() -> reranker.rerank(
                "query", List.of(new RagSource("doc-0", "first")), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("合法候选索引");
    }

    private ClientResponse jsonResponse(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
