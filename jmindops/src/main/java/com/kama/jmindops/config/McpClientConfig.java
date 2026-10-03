package com.kama.jmindops.config;

import com.kama.jmindops.ai.ToolCallback;
import com.kama.jmindops.ai.ToolCallbackProvider;
import com.kama.jmindops.ai.ToolContext;
import com.kama.jmindops.ai.ToolDefinition;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class McpClientConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.ai.mcp.client.enabled", havingValue = "true")
    McpCallbackProvider mcpCallbackProvider(Environment environment) {
        Map<String, StdioConnection> connections = Binder.get(environment)
                .bind("app.ai.mcp.client.stdio.connections", Bindable.mapOf(String.class, StdioConnection.class))
                .orElse(Map.of());
        List<McpClient> clients = new ArrayList<>();
        try {
            connections.forEach((name, connection) -> {
                List<String> command = new ArrayList<>();
                command.add(connection.command());
                command.addAll(connection.args());
                clients.add(DefaultMcpClient.builder().key(name)
                        .transport(StdioMcpTransport.builder().command(command).logEvents(false).build()).build());
            });
            return new McpCallbackProvider(clients);
        } catch (RuntimeException exception) {
            for (McpClient client : clients) {
                try { client.close(); } catch (Exception cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
    }

    public record StdioConnection(String command, List<String> args) {
        public StdioConnection { args = args == null ? List.of() : List.copyOf(args); }
    }

    /** Discovery does not execute tools; external governance still wraps every callback. */
    public static final class McpCallbackProvider implements ToolCallbackProvider, AutoCloseable {
        private final List<McpClient> clients;
        public McpCallbackProvider(List<McpClient> clients) { this.clients = List.copyOf(clients); }
        @Override public ToolCallback[] getToolCallbacks() {
            List<ToolCallback> callbacks = new ArrayList<>();
            for (McpClient client : clients) {
                client.listTools().forEach(specification -> callbacks.add(new ToolCallback() {
                    @Override public ToolDefinition getToolDefinition() { return new ToolDefinition(specification); }
                    @Override public String call(String arguments) { return call(arguments, new ToolContext(Map.of())); }
                    @Override public String call(String arguments, ToolContext context) {
                        String callId = String.valueOf(context.getContext().getOrDefault("tool_call_id", UUID.randomUUID().toString()));
                        var result = client.executeTool(ToolExecutionRequest.builder().id(callId)
                                .name(specification.name()).arguments(arguments).build());
                        if (result.isError()) throw new IllegalStateException("MCP tool returned an error: " + specification.name());
                        return result.resultText();
                    }
                }));
            }
            return callbacks.toArray(ToolCallback[]::new);
        }
        @Override public void close() throws Exception {
            Exception failure = null;
            for (McpClient client : clients) {
                try { client.close(); } catch (Exception exception) {
                    if (failure == null) failure = exception; else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
