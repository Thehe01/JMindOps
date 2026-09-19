package com.kama.jmindops.agent;

import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.model.dto.AgentDTO;
import com.kama.jmindops.model.entity.Agent;
import com.kama.jmindops.service.ToolFacadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JMindOpsFactoryToolRoutingTest {

    private ToolFacadeService toolFacadeService;
    private JMindOpsFactory factory;
    private Tool knowledgeTool;
    private Tool terminateTool;
    private Tool databaseTool;

    @BeforeEach
    void setUp() {
        toolFacadeService = mock(ToolFacadeService.class);
        knowledgeTool = tool("KnowledgeTool");
        terminateTool = tool("terminate");
        databaseTool = tool("dataBaseTool");
        when(toolFacadeService.getFixedTools()).thenReturn(List.of(knowledgeTool, terminateTool));
        when(toolFacadeService.getOptionalTools()).thenReturn(List.of(databaseTool));

        ExternalToolRegistry externalToolRegistry = mock(ExternalToolRegistry.class);
        when(externalToolRegistry.providers()).thenReturn(List.of());
        factory = new JMindOpsFactory(
                null, null, null, null, null, toolFacadeService,
                null, null, null, null, null, null, externalToolRegistry, 120
        );
    }

    @Test
    void mcpKeepsExplicitInternalToolsAndHidesKnowledgeTool() {
        AgentDTO config = config(List.of("dataBaseTool"), List.of("kb-1"));
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent");
        when(agent.getSystemPrompt()).thenReturn("base");

        factory.applyRoutingDecision(agent, config, RoutingDecision.MCP);
        List<Tool> tools = factory.resolveRuntimeTools(config, RoutingDecision.MCP);

        assertThat(config.getAllowedTools()).containsExactly("dataBaseTool");
        assertThat(config.getAllowedKbs()).isEmpty();
        assertThat(tools).containsExactly(databaseTool);
    }

    @Test
    void knowledgeToolRequiresRagRouteAndAuthorizedKnowledgeBase() {
        assertThat(factory.resolveRuntimeTools(config(List.of(), List.of("kb-1")), RoutingDecision.RAG))
                .contains(knowledgeTool);
        assertThat(factory.resolveRuntimeTools(config(List.of(), List.of()), RoutingDecision.RAG))
                .doesNotContain(knowledgeTool);
        assertThat(factory.resolveRuntimeTools(config(List.of(), List.of("kb-1")), RoutingDecision.CHAT))
                .doesNotContain(knowledgeTool);
        assertThat(factory.resolveRuntimeTools(config(List.of(), List.of("kb-1")), RoutingDecision.RAG))
                .doesNotContain(terminateTool);
    }

    private static AgentDTO config(List<String> tools, List<String> knowledgeBases) {
        return AgentDTO.builder()
                .allowedTools(tools)
                .allowedKbs(knowledgeBases)
                .chatOptions(AgentDTO.ChatOptions.defaultOptions())
                .build();
    }

    private static Tool tool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }
}
