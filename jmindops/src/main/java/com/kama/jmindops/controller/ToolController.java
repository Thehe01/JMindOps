package com.kama.jmindops.controller;

import com.kama.jmindops.agent.ExternalToolRegistry;
import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.vo.ToolVO;
import com.kama.jmindops.service.ToolFacadeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ToolController {

    private final ToolFacadeService toolFacadeService;
    private final ExternalToolRegistry externalToolRegistry;
    public ToolController(ToolFacadeService toolFacadeService, ExternalToolRegistry externalToolRegistry) {
        this.toolFacadeService = toolFacadeService;
        this.externalToolRegistry = externalToolRegistry;
    }


    // 给前端提供的可选的工具列表
    @GetMapping("/tools")
    public ApiResponse<List<ToolVO>> getOptionalTools() {
        Map<String, ToolVO> tools = new LinkedHashMap<>();
        toolFacadeService.getOptionalTools().stream()
                .map(ToolVO::from)
                .forEach(tool -> tools.putIfAbsent(tool.name(), tool));
        externalToolRegistry.callbacks().stream()
                .map(ToolVO::from)
                .forEach(tool -> tools.putIfAbsent(tool.name(), tool));
        return ApiResponse.success(List.copyOf(tools.values()));
    }
}
