package com.kama.jmindops.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jmindops.converter.AgentConverter;
import com.kama.jmindops.agent.ExternalToolRegistry;
import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.AgentMapper;
import com.kama.jmindops.model.dto.AgentDTO;
import com.kama.jmindops.model.entity.Agent;
import com.kama.jmindops.model.request.CreateAgentRequest;
import com.kama.jmindops.model.request.UpdateAgentRequest;
import com.kama.jmindops.model.response.CreateAgentResponse;
import com.kama.jmindops.model.response.GetAgentsResponse;
import com.kama.jmindops.model.vo.AgentVO;
import com.kama.jmindops.service.AgentFacadeService;
import com.kama.jmindops.service.ToolFacadeService;
import com.kama.jmindops.security.AuthenticatedUser;
import com.kama.jmindops.security.ResourceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class AgentFacadeServiceImpl implements AgentFacadeService {

    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final ResourceAccessService resourceAccessService;
    private final ToolFacadeService toolFacadeService;
    private final ExternalToolRegistry externalToolRegistry;
    public AgentFacadeServiceImpl(
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            ResourceAccessService resourceAccessService,
            ToolFacadeService toolFacadeService,
            ExternalToolRegistry externalToolRegistry
    ) {
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.resourceAccessService = resourceAccessService;
        this.toolFacadeService = toolFacadeService;
        this.externalToolRegistry = externalToolRegistry;
    }


    private static final Set<String> ADMIN_ONLY_TOOLS = Set.of("dataBaseTool", "emailTool");

    @Override
    public GetAgentsResponse getAgents() {
        String ownerId = resourceAccessService.currentUserId();
        List<Agent> agents = agentMapper.selectAll().stream()
                .filter(agent -> ownerId.equals(agent.getOwnerId()))
                .toList();
        List<AgentVO> result = new ArrayList<>();
        for (Agent agent : agents) {
            try {
                AgentVO vo = agentConverter.toVO(agent);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetAgentsResponse.builder()
                .agents(result.toArray(new AgentVO[0]))
                .build();
    }

    @Override
    public CreateAgentResponse createAgent(CreateAgentRequest request) {
        try {
            validateAllowedTools(request.getAllowedTools());
            validateKnowledgeBaseOwnership(request.getAllowedKbs());
            // 将 CreateAgentRequest 转换为 AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(request);
            
            // 将 AgentDTO 转换为 Agent 实体
            Agent agent = agentConverter.toEntity(agentDTO);
            
            // 设置创建时间和更新时间
            LocalDateTime now = LocalDateTime.now();
            agent.setOwnerId(resourceAccessService.currentUserId());
            agent.setCreatedAt(now);
            agent.setUpdatedAt(now);
            
            // 插入数据库，ID 由数据库自动生成
            int result = agentMapper.insert(agent);
            if (result <= 0) {
                throw new BizException("创建 agent 失败");
            }
            
            // 返回生成的 agentId
            return CreateAgentResponse.builder()
                    .agentId(agent.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建 agent 时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteAgent(String agentId) {
        Agent agent = resourceAccessService.requireOwnedAgent(agentId);
        
        int result = agentMapper.deleteById(agentId);
        if (result <= 0) {
            throw new BizException("删除 agent 失败");
        }
    }

    @Override
    public void updateAgent(String agentId, UpdateAgentRequest request) {
        try {
            validateAllowedTools(request.getAllowedTools());
            validateKnowledgeBaseOwnership(request.getAllowedKbs());
            // 查询现有的 agent
            Agent existingAgent = resourceAccessService.requireOwnedAgent(agentId);
            
            // 将现有 Agent 转换为 AgentDTO
            AgentDTO agentDTO = agentConverter.toDTO(existingAgent);
            
            // 使用 UpdateAgentRequest 更新 AgentDTO
            agentConverter.updateDTOFromRequest(agentDTO, request);
            
            // 将更新后的 AgentDTO 转换回 Agent 实体
            Agent updatedAgent = agentConverter.toEntity(agentDTO);
            
            // 保留原有的 ID 和创建时间
            updatedAgent.setId(existingAgent.getId());
            updatedAgent.setCreatedAt(existingAgent.getCreatedAt());
            updatedAgent.setUpdatedAt(LocalDateTime.now());
            
            // 更新数据库
            int result = agentMapper.updateById(updatedAgent);
            if (result <= 0) {
                throw new BizException("更新 agent 失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新 agent 时发生序列化错误: " + e.getMessage());
        }
    }

    private void validateKnowledgeBaseOwnership(List<String> knowledgeBaseIds) {
        if (knowledgeBaseIds == null) return;
        knowledgeBaseIds.forEach(resourceAccessService::requireOwnedKnowledgeBase);
    }

    private void validateAllowedTools(List<String> allowedToolNames) {
        if (allowedToolNames == null) {
            return;
        }

        Set<String> registeredOptionalTools = toolFacadeService.getOptionalTools().stream()
                .map(Tool::getName)
                .collect(Collectors.toCollection(HashSet::new));
        registeredOptionalTools.addAll(externalToolRegistry.names());
        boolean admin = currentUserIsAdmin();

        for (String toolName : allowedToolNames) {
            if (ADMIN_ONLY_TOOLS.contains(toolName) && !admin) {
                throw new BizException(403, "仅管理员可以绑定高风险工具: " + toolName);
            }
            if (!registeredOptionalTools.contains(toolName)) {
                throw new BizException("工具不存在或当前未启用: " + toolName);
            }
        }
    }

    private boolean currentUserIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user
                && "ADMIN".equalsIgnoreCase(user.role());
    }
}
