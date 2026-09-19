package com.kama.jmindops.governance;

import com.kama.jmindops.agent.tools.DataBaseTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolGovernanceCallbackTest {

    @Test
    void blocksSpringAiCallbackBeforeDatabaseMethodRuns() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataBaseTools tool = new DataBaseTools(jdbcTemplate);
        ToolCallback rawCallback = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks()[0];
        ToolGovernanceService governanceService = mock(ToolGovernanceService.class);
        when(governanceService.requireApproval(anyString(), any(Object[].class), anyString()))
                .thenReturn(new ToolGovernanceService.ApprovalDecision(false, "approval-123"));

        ToolCallback callback = ToolGovernanceCallback.wrapIfRequired(
                tool, rawCallback, governanceService);
        String result = callback.call("{\"sql\":\"SELECT id FROM knowledge_base\"}");

        assertThat(ToolApprovalSignal.isWaitingResponse(result)).isTrue();
        verify(governanceService).requireApproval(
                org.mockito.ArgumentMatchers.eq("databaseQuery"),
                any(Object[].class),
                org.mockito.ArgumentMatchers.eq("HIGH"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void findsApprovalOnlyForAnnotatedCallbackName() {
        DataBaseTools tool = new DataBaseTools(mock(JdbcTemplate.class));
        ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks()[0];

        assertThat(ToolGovernanceCallback.findApprovalAnnotation(
                tool, callback.getToolDefinition().name())).isNotNull();
        assertThat(ToolGovernanceCallback.findApprovalAnnotation(
                tool, "missingTool")).isNull();
    }

    @Test
    void externalCallbackAlwaysRequiresApprovalBeforeInvocation() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(definition.name()).thenReturn("externalWrite");
        ToolGovernanceService governanceService = mock(ToolGovernanceService.class);
        when(governanceService.requireApproval(anyString(), any(Object[].class), anyString()))
                .thenReturn(new ToolGovernanceService.ApprovalDecision(false, "approval-456"));

        ToolCallback callback = ToolGovernanceCallback.wrapExternal(delegate, governanceService);
        String result = callback.call("{\"path\":\"output.txt\"}");

        assertThat(ToolApprovalSignal.isWaitingResponse(result)).isTrue();
        verify(governanceService).requireApproval(
                org.mockito.ArgumentMatchers.eq("externalWrite"),
                any(Object[].class),
                org.mockito.ArgumentMatchers.eq("HIGH"));
        verify(delegate, never()).call(anyString());
    }
}
