package com.kama.jmindops.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.agent.AgentExecutionPolicy;
import com.kama.jmindops.agent.AgentState;
import com.kama.jmindops.agent.JMindOps;
import com.kama.jmindops.event.AgentMessageGeneratedEvent;
import com.kama.jmindops.ai.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Real LangChain4j HTTP/SSE codecs against a local fixture, with no external model calls. */
class LangChain4jAgentWireTest {
    @Test
    void nativeStreamCompletesOneGovernedToolRoundAndPreservesIdsAndUsage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            boolean hasResult = false;
            for (JsonNode message : request.get("messages")) {
                if ("tool".equals(message.path("role").asText())) hasResult = true;
            }
            List<Map<String, Object>> deltas = hasResult
                    ? List.of(Map.of("content", "finished"))
                    : List.of(
                    Map.of("tool_calls", List.of(Map.of("index", 0, "id", "native-call", "type", "function",
                            "function", Map.of("name", "echo", "arguments", "{\"value\":")))),
                    Map.of("tool_calls", List.of(Map.of("index", 0, "function", Map.of("arguments", "\"hello\"}")))));
            StringBuilder body = new StringBuilder();
            for (Map<String, Object> delta : deltas) {
                body.append("data: ").append(mapper.writeValueAsString(Map.of("id", "response-1", "model", "fixture",
                        "choices", List.of(Map.of("index", 0, "delta", delta))))).append("\n\n");
            }
            body.append("data: ").append(mapper.writeValueAsString(Map.of("id", "response-1", "model", "fixture",
                    "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", hasResult ? "stop" : "tool_calls")),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 20, "total_tokens", 30)))).append("\n\n");
            body.append("data: [DONE]\n\n");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            ChatClient client = new ChatClient(
                    OpenAiChatModel.builder().apiKey("fixture").baseUrl(url).modelName("fixture").maxRetries(0).build(),
                    OpenAiStreamingChatModel.builder().apiKey("fixture").baseUrl(url).modelName("fixture").build());
            AtomicInteger calls = new AtomicInteger();
            ToolCallback callback = MethodToolCallbackProvider.builder().toolObjects(new EchoFixture(calls)).build().getToolCallbacks()[0];
            List<AgentMessageGeneratedEvent> generated = new ArrayList<>();
            JMindOps runtime = new JMindOps("agent", "agent", "", "persona", client, 20, 0.1, 0.8,
                    List.of(new UserMessage("echo hello")), List.of(callback), List.of(), "session", "generation",
                    event -> { if (event instanceof AgentMessageGeneratedEvent message) generated.add(message); },
                    null, null, null, AgentExecutionPolicy.Plan.none(), Duration.ofSeconds(5));
            runtime.run();

            assertThat(runtime.getAgentState()).isEqualTo(AgentState.FINISHED);
            assertThat(calls).hasValue(1);
            assertThat(requests).hasSize(2);
            JsonNode toolResult = null;
            JsonNode assistantCall = null;
            for (JsonNode message : requests.get(1).get("messages")) {
                if ("tool".equals(message.path("role").asText())) toolResult = message;
                if (message.has("tool_calls")) assistantCall = message;
            }
            assertThat(toolResult).isNotNull();
            assertThat(toolResult.path("tool_call_id").asText()).isEqualTo("native-call");
            assertThat(toolResult.path("content").asText()).isEqualTo("hello");
            assertThat(assistantCall.path("tool_calls").get(0).path("function").path("arguments").asText())
                    .isEqualTo("{\"value\":\"hello\"}");
            assertThat(runtime.getCumulativeTokens()).isEqualTo(60);
            assertThat(generated.get(generated.size() - 1).getGeneratedMessage().getText()).isEqualTo("finished");
        } finally {
            server.stop(0);
        }
    }

    public static final class EchoFixture {
        private final AtomicInteger calls;
        public EchoFixture(AtomicInteger calls) { this.calls = calls; }
        @dev.langchain4j.agent.tool.Tool(name = "echo", value = "Echo a value")
        public String echo(String value) { calls.incrementAndGet(); return value; }
    }
}
