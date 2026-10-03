package com.kama.jmindops.config;

import com.kama.jmindops.ai.ToolContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LangChain4jMcpTest {
    @Test
    void nativeDiscoveryDoesNotExecuteToolsAndCallsUseTheLedgerId() throws Exception {
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("readFile").build()));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().resultText("contents").build());
        var provider = new McpClientConfig.McpCallbackProvider(List.of(client));
        var callback = provider.getToolCallbacks()[0];
        verify(client, never()).executeTool(any(ToolExecutionRequest.class));
        assertThat(callback.call("{\"path\":\"todo.md\"}", new ToolContext(Map.of("tool_call_id", "stable-call"))))
                .isEqualTo("contents");
        ArgumentCaptor<ToolExecutionRequest> request = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(client).executeTool(request.capture());
        assertThat(request.getValue().id()).isEqualTo("stable-call");
        assertThat(request.getValue().name()).isEqualTo("readFile");
        assertThat(request.getValue().arguments()).isEqualTo("{\"path\":\"todo.md\"}");
        provider.close();
        verify(client).close();
    }

    @Test
    void protocolErrorIsPropagatedInsteadOfRecordedAsSuccess() {
        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name("writeFile").build()));
        when(client.executeTool(any(ToolExecutionRequest.class)))
                .thenReturn(ToolExecutionResult.builder().isError(true).resultText("failed").build());
        var callback = new McpClientConfig.McpCallbackProvider(List.of(client)).getToolCallbacks()[0];
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("writeFile");
    }
}
