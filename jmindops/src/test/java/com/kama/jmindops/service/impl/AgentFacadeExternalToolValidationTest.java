package com.kama.jmindops.service.impl;

import com.kama.jmindops.agent.ExternalToolRegistry;
import com.kama.jmindops.converter.AgentConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.AgentMapper;
import com.kama.jmindops.model.dto.AgentDTO;
import com.kama.jmindops.model.entity.Agent;
import com.kama.jmindops.model.request.CreateAgentRequest;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.ToolFacadeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentFacadeExternalToolValidationTest {

    @Test
    void acceptsDiscoveredExternalCallbackNameAndRejectsUnknownName() throws Exception {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentConverter converter = mock(AgentConverter.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        ToolFacadeService tools = mock(ToolFacadeService.class);
        ExternalToolRegistry externalTools = mock(ExternalToolRegistry.class);
        AgentFacadeServiceImpl service = new AgentFacadeServiceImpl(
                mapper, converter, access, tools, externalTools);
        when(tools.getOptionalTools()).thenReturn(List.of());
        when(externalTools.names()).thenReturn(Set.of("mcp_read_file"));
        when(access.currentUserId()).thenReturn("owner-1");
        AgentDTO dto = AgentDTO.builder().allowedTools(List.of("mcp_read_file")).build();
        Agent agent = Agent.builder().id("agent-1").build();
        CreateAgentRequest request = CreateAgentRequest.builder()
                .name("external-agent")
                .systemPrompt("use external tools")
                .model("deepseek-chat")
                .allowedTools(List.of("mcp_read_file"))
                .build();
        when(converter.toDTO(request)).thenReturn(dto);
        when(converter.toEntity(dto)).thenReturn(agent);
        when(mapper.insert(agent)).thenReturn(1);

        assertThat(service.createAgent(request).getAgentId()).isEqualTo("agent-1");

        request.setAllowedTools(List.of("unknown_external_tool"));
        assertThatThrownBy(() -> service.createAgent(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工具不存在");
    }
}
