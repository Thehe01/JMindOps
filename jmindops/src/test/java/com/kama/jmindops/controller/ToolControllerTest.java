package com.kama.jmindops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.agent.ExternalToolRegistry;
import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.agent.tools.ToolType;
import com.kama.jmindops.model.vo.ToolVO;
import com.kama.jmindops.service.ToolFacadeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolControllerTest {

    @Test
    void mapsToolBeansToSerializableApiModels() throws Exception {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("dataBaseTool");
        when(tool.getDescription()).thenReturn("只读数据库查询");
        when(tool.getType()).thenReturn(ToolType.OPTIONAL);
        ToolFacadeService service = mock(ToolFacadeService.class);
        when(service.getOptionalTools()).thenReturn(List.of(tool));
        ExternalToolRegistry externalToolRegistry = mock(ExternalToolRegistry.class);
        when(externalToolRegistry.callbacks()).thenReturn(List.of());

        var response = new ToolController(service, externalToolRegistry).getOptionalTools();

        assertThat(response.getData()).containsExactly(
                new ToolVO("dataBaseTool", "只读数据库查询", ToolType.OPTIONAL));
        assertThat(new ObjectMapper().writeValueAsString(response))
                .isEqualTo("{\"code\":200,\"message\":\"success\",\"data\":[{\"name\":\"dataBaseTool\",\"description\":\"只读数据库查询\",\"type\":\"OPTIONAL\"}]}");
    }
}
