package com.kama.jmindops.controller;

import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.request.RetryGenerationTaskRequest;
import com.kama.jmindops.model.response.CreateChatMessageResponse;
import com.kama.jmindops.model.response.GenerationTaskResponse;
import com.kama.jmindops.model.response.AgentTraceResponse;
import com.kama.jmindops.service.GenerationTaskFacadeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/generation-tasks")
public class GenerationTaskController {
    private final GenerationTaskFacadeService generationTaskFacadeService;

    public GenerationTaskController(GenerationTaskFacadeService generationTaskFacadeService) {
        this.generationTaskFacadeService = generationTaskFacadeService;
    }

    @GetMapping("/{generationId}")
    public ApiResponse<GenerationTaskResponse> getTask(@PathVariable String generationId) {
        return ApiResponse.success(generationTaskFacadeService.getOwnedTask(generationId));
    }

    @GetMapping("/{generationId}/trace")
    public ApiResponse<AgentTraceResponse> getTrace(@PathVariable String generationId) {
        return ApiResponse.success(generationTaskFacadeService.getOwnedTrace(generationId));
    }

    @PostMapping("/{generationId}/retry")
    public ApiResponse<CreateChatMessageResponse> retry(
            @PathVariable String generationId,
            @Valid @RequestBody(required = false) RetryGenerationTaskRequest request
    ) {
        return ApiResponse.success(generationTaskFacadeService.retry(generationId, request));
    }
}
