package com.kama.jmindops.controller;

import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.service.ToolFacadeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ToolController {

    private final ToolFacadeService toolFacadeService;
    public ToolController(ToolFacadeService toolFacadeService) {
        this.toolFacadeService = toolFacadeService;
    }


    // 给前端提供的可选的工具列表
    @GetMapping("/tools")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<Tool>> getOptionalTools() {
        return ApiResponse.success(toolFacadeService.getOptionalTools());
    }
}
