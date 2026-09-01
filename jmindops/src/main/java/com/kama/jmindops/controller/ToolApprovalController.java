package com.kama.jmindops.controller;

import com.kama.jmindops.governance.ToolGovernanceService;
import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.ChatMessageFacadeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tool-approvals")
public class ToolApprovalController {
    private final ToolGovernanceService toolGovernanceService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ResourceAccessService resourceAccessService;
    public ToolApprovalController(ToolGovernanceService toolGovernanceService, ChatMessageFacadeService chatMessageFacadeService, ResourceAccessService resourceAccessService) {
        this.toolGovernanceService = toolGovernanceService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.resourceAccessService = resourceAccessService;
    }


    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(toolGovernanceService.getApprovalsForCurrentUser(status));
    }

    @PostMapping("/{approvalId}/approve")
    public ApiResponse<Void> approve(@PathVariable String approvalId) {
        ToolGovernanceService.ApprovalRecord record = toolGovernanceService.decide(approvalId, true);
        ChatSession session = resourceAccessService.requireOwnedChatSession(record.sessionId());
        
        // 自动触发 Agent 恢复执行并流式推流
        String resumePrompt = String.format("【审批通过】用户已在管理面板批准执行工具「%s」（单号：%s），请立即继续执行该工具并给出最终结果。",
                record.toolName(), approvalId);
        chatMessageFacadeService.createChatMessage(CreateChatMessageRequest.builder()
                .sessionId(record.sessionId())
                .agentId(session.getAgentId())
                .content(resumePrompt)
                .build());
        return ApiResponse.success();
    }

    @PostMapping("/{approvalId}/reject")
    public ApiResponse<Void> reject(@PathVariable String approvalId) {
        ToolGovernanceService.ApprovalRecord record = toolGovernanceService.decide(approvalId, false);
        ChatSession session = resourceAccessService.requireOwnedChatSession(record.sessionId());
        
        // 自动触发 Agent 调整决策并告知用户
        String rejectPrompt = String.format("【审批拒绝】用户已在管理面板拒绝执行工具「%s」（单号：%s），请放弃该工具调用并告知用户已取消操作。",
                record.toolName(), approvalId);
        chatMessageFacadeService.createChatMessage(CreateChatMessageRequest.builder()
                .sessionId(record.sessionId())
                .agentId(session.getAgentId())
                .content(rejectPrompt)
                .build());
        return ApiResponse.success();
    }
}
