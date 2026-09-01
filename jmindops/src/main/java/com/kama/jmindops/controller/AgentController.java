package com.kama.jmindops.controller;

import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.request.CreateAgentRequest;
import com.kama.jmindops.model.request.UpdateAgentRequest;
import com.kama.jmindops.model.response.CreateAgentResponse;
import com.kama.jmindops.model.response.GetAgentsResponse;
import com.kama.jmindops.service.AgentFacadeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final AgentFacadeService agentFacadeService;
    public AgentController(AgentFacadeService agentFacadeService) {
        this.agentFacadeService = agentFacadeService;
    }


    // 查询 agents
    @GetMapping("/agents")
    public ApiResponse<GetAgentsResponse> getAgents() {
        return ApiResponse.success(agentFacadeService.getAgents());
    }

    // 创建 agent
    @PostMapping("/agents")
    public ApiResponse<CreateAgentResponse> createAgent(@Valid @RequestBody CreateAgentRequest request) {
        return ApiResponse.success(agentFacadeService.createAgent(request));
    }

    // 删除 agent
    @DeleteMapping("/agents/{agentId}")
    public ApiResponse<Void> deleteAgent(@PathVariable String agentId) {
        agentFacadeService.deleteAgent(agentId);
        return ApiResponse.success();
    }

    // 更新 agent
    @PatchMapping("/agents/{agentId}")
    public ApiResponse<Void> updateAgent(@PathVariable String agentId, @Valid @RequestBody UpdateAgentRequest request) {
        agentFacadeService.updateAgent(agentId, request);
        return ApiResponse.success();
    }
}
