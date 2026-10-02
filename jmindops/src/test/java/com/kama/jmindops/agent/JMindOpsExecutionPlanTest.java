package com.kama.jmindops.agent;

import org.junit.jupiter.api.Test;
import com.kama.jmindops.ai.ChatClient;
import com.kama.jmindops.ai.ToolResponseMessage;
import com.kama.jmindops.ai.UserMessage;
import com.kama.jmindops.ai.ToolCallingChatOptions;
import com.kama.jmindops.ai.ToolCallback;
import com.kama.jmindops.ai.ToolDefinition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JMindOpsExecutionPlanTest {

    @Test
    void replacesToolCallbacksAsThePlanAdvancesAndClearsThemAtCompletion() throws Exception {
        ToolCallback listFiles = toolCallback("listFiles");
        ToolCallback readFile = toolCallback("readFile");
        String request = "首先查看 notes 文件夹列表，然后打开 todo.md。";
        JMindOps runtime = new JMindOps(
                "agent", "agent", "", "", mock(ChatClient.class),
                20, 0.0, 0.9,
                List.of(new UserMessage(request)),
                List.of(listFiles, readFile), List.of(),
                "session", "generation", null, null,
                RoutingDecision.MCP, request,
                AgentExecutionPolicy.plan(RoutingDecision.MCP, request),
                Duration.ofSeconds(60));

        assertThat(configuredToolNames(runtime)).containsExactly("listFiles");

        advance(runtime, "listFiles");
        assertThat(configuredToolNames(runtime)).containsExactly("readFile");

        advance(runtime, "readFile");
        assertThat(configuredToolNames(runtime)).isEmpty();
    }

    private static ToolCallback toolCallback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private static List<String> configuredToolNames(JMindOps runtime) throws Exception {
        Field field = JMindOps.class.getDeclaredField("chatOptions");
        field.setAccessible(true);
        ToolCallingChatOptions options = (ToolCallingChatOptions) field.get(runtime);
        return options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static void advance(JMindOps runtime, String toolName) throws Exception {
        Method method = JMindOps.class.getDeclaredMethod(
                "advanceExecutionPlan", ToolResponseMessage.class);
        method.setAccessible(true);
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-" + toolName, toolName, "ok")))
                .build();
        method.invoke(runtime, response);
    }
}
