package com.kama.jmindops.controller;

import com.kama.jmindops.governance.ToolGovernanceService;
import com.kama.jmindops.model.common.ApiResponse;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.model.request.CreateChatMessageRequest;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.AgentResumeService;
import com.kama.jmindops.service.ChatMessageFacadeService;
import com.kama.jmindops.service.GenerationTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/tool-approvals")
public class ToolApprovalController {
    private static final Logger log = LoggerFactory.getLogger(ToolApprovalController.class);

    private final ToolGovernanceService toolGovernanceService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ResourceAccessService resourceAccessService;
    private final GenerationTaskStore generationTaskStore;
    private final AgentResumeService agentResumeService;

    public ToolApprovalController(
            ToolGovernanceService toolGovernanceService,
            ChatMessageFacadeService chatMessageFacadeService,
            ResourceAccessService resourceAccessService
    ) {
        this(toolGovernanceService, chatMessageFacadeService, resourceAccessService, null, null);
    }

    @Autowired
    public ToolApprovalController(
            ToolGovernanceService toolGovernanceService,
            ChatMessageFacadeService chatMessageFacadeService,
            ResourceAccessService resourceAccessService,
            @Autowired(required = false) GenerationTaskStore generationTaskStore,
            @Autowired(required = false) AgentResumeService agentResumeService
    ) {
        this.toolGovernanceService = toolGovernanceService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.resourceAccessService = resourceAccessService;
        this.generationTaskStore = generationTaskStore;
        this.agentResumeService = agentResumeService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(toolGovernanceService.getApprovalsForCurrentUser(status));
    }

    @PostMapping("/{approvalId}/approve")
    @Transactional
    public ApiResponse<Void> approve(@PathVariable String approvalId) {
        ToolGovernanceService.ApprovalRecord record = toolGovernanceService.decide(approvalId, true);
        ChatSession session = resourceAccessService.requireOwnedChatSession(record.sessionId());

        // P0-2: Check if there is an active generation in WAITING_APPROVAL status
        Optional<GenerationTask> waitingTask = Optional.empty();
        if (record.generationId() != null && generationTaskStore != null) {
            waitingTask = generationTaskStore.findExecutionTask(record.generationId())
                    .filter(t -> t.status() == GenerationTask.Status.WAITING_APPROVAL);
        }
        if (waitingTask.isEmpty() && generationTaskStore != null) {
            waitingTask = generationTaskStore.findWaitingApprovalForSession(record.sessionId());
        }

        if (waitingTask.isPresent() && agentResumeService != null) {
            String targetGenerationId = waitingTask.get().id();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info("Tool approval committed, triggering async agent resume: generationId={}", targetGenerationId);
                        CompletableFuture.runAsync(() -> {
                            try {
                                agentResumeService.resume(targetGenerationId);
                            } catch (Exception e) {
                                log.error("Async agent resume failed after tool approval commit: generationId={}", targetGenerationId, e);
                            }
                        });
                    }
                });
            } else {
                // If not running in an active transaction (e.g. unit tests without Spring transaction manager)
                agentResumeService.resume(targetGenerationId);
            }
            return ApiResponse.success();
        }

        // Fallback for legacy flows without checkpointing
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
    @Transactional
    public ApiResponse<Void> reject(@PathVariable String approvalId) {
        ToolGovernanceService.ApprovalRecord record = toolGovernanceService.decide(approvalId, false);
        ChatSession session = resourceAccessService.requireOwnedChatSession(record.sessionId());

        // P0-2: Check if there is an active generation in WAITING_APPROVAL status and mark it failed
        Optional<GenerationTask> waitingTask = Optional.empty();
        if (record.generationId() != null && generationTaskStore != null) {
            waitingTask = generationTaskStore.findExecutionTask(record.generationId())
                    .filter(t -> t.status() == GenerationTask.Status.WAITING_APPROVAL);
        }
        if (waitingTask.isEmpty() && generationTaskStore != null) {
            waitingTask = generationTaskStore.findWaitingApprovalForSession(record.sessionId());
        }
        if (waitingTask.isPresent() && generationTaskStore != null) {
            generationTaskStore.markFailed(waitingTask.get().id(), "审批已被拒绝: " + record.toolName());
        }

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
