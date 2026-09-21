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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolApprovalControllerTest {

    private ToolGovernanceService toolGovernanceService;
    private ChatMessageFacadeService chatMessageFacadeService;
    private ResourceAccessService resourceAccessService;
    private GenerationTaskStore generationTaskStore;
    private AgentResumeService agentResumeService;
    private ToolApprovalController controller;

    @BeforeEach
    void setUp() {
        toolGovernanceService = mock(ToolGovernanceService.class);
        chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        resourceAccessService = mock(ResourceAccessService.class);
        generationTaskStore = mock(GenerationTaskStore.class);
        agentResumeService = mock(AgentResumeService.class);

        controller = new ToolApprovalController(
                toolGovernanceService,
                chatMessageFacadeService,
                resourceAccessService,
                generationTaskStore,
                agentResumeService
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void listReturnsApprovalsForCurrentUser() {
        when(toolGovernanceService.getApprovalsForCurrentUser("PENDING"))
                .thenReturn(List.of(Map.of("id", "approval-1", "status", "PENDING")));

        ApiResponse<List<Map<String, Object>>> response = controller.list("PENDING");

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0)).containsEntry("id", "approval-1");
    }

    @Test
    void approveDecouplesTransactionAndResumesAgentAfterCommit() {
        String approvalId = "approval-tx-1";
        String sessionId = "session-1";
        String genId = "gen-waiting-1";

        ToolGovernanceService.ApprovalRecord record = new ToolGovernanceService.ApprovalRecord(
                approvalId, sessionId, "dangerousTool", true, genId
        );
        when(toolGovernanceService.decide(approvalId, true)).thenReturn(record);
        when(resourceAccessService.requireOwnedChatSession(sessionId))
                .thenReturn(ChatSession.builder().id(sessionId).agentId("agent-1").ownerId("user-1").build());

        GenerationTask waitingTask = new GenerationTask(
                genId, null, "user-1", "alice", "USER", "req-1", "fp-1",
                "agent-1", sessionId, "msg-1", "input", GenerationTask.Status.WAITING_APPROVAL,
                1, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(generationTaskStore.findExecutionTask(genId)).thenReturn(Optional.of(waitingTask));

        // Activate transaction synchronization
        TransactionSynchronizationManager.initSynchronization();

        ApiResponse<Void> response = controller.approve(approvalId);
        assertThat(response.getCode()).isEqualTo(200);

        // Before transaction commit: resume MUST NOT have been called!
        verify(agentResumeService, never()).resume(any());

        // Simulate database transaction COMMIT
        TransactionSynchronizationUtils.triggerAfterCommit();

        // After commit: resume is triggered asynchronously!
        verify(agentResumeService, timeout(5000).times(1)).resume(genId);
        // Legacy message creation should NOT be called when task was resumed
        verify(chatMessageFacadeService, never()).createChatMessage((CreateChatMessageRequest) any());
    }

    @Test
    void approveTriggersAsyncResumeWhenNoActiveTransaction() {
        String approvalId = "approval-no-tx";
        String sessionId = "session-2";
        String genId = "gen-waiting-2";

        ToolGovernanceService.ApprovalRecord record = new ToolGovernanceService.ApprovalRecord(
                approvalId, sessionId, "cityTool", true, genId
        );
        when(toolGovernanceService.decide(approvalId, true)).thenReturn(record);
        when(resourceAccessService.requireOwnedChatSession(sessionId))
                .thenReturn(ChatSession.builder().id(sessionId).agentId("agent-1").ownerId("user-1").build());

        GenerationTask waitingTask = new GenerationTask(
                genId, null, "user-1", "alice", "USER", "req-2", "fp-2",
                "agent-1", sessionId, "msg-2", "input", GenerationTask.Status.WAITING_APPROVAL,
                1, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(generationTaskStore.findExecutionTask(genId)).thenReturn(Optional.of(waitingTask));

        ApiResponse<Void> response = controller.approve(approvalId);
        assertThat(response.getCode()).isEqualTo(200);

        verify(agentResumeService, timeout(5000).times(1)).resume(genId);
    }

    @Test
    void approveFallsBackToLegacyPromptWhenNoWaitingTask() {
        String approvalId = "approval-legacy";
        String sessionId = "session-3";

        ToolGovernanceService.ApprovalRecord record = new ToolGovernanceService.ApprovalRecord(
                approvalId, sessionId, "customTool", true, null
        );
        when(toolGovernanceService.decide(approvalId, true)).thenReturn(record);
        when(resourceAccessService.requireOwnedChatSession(sessionId))
                .thenReturn(ChatSession.builder().id(sessionId).agentId("agent-1").ownerId("user-1").build());
        when(generationTaskStore.findWaitingApprovalForSession(sessionId)).thenReturn(Optional.empty());

        ApiResponse<Void> response = controller.approve(approvalId);
        assertThat(response.getCode()).isEqualTo(200);

        verify(chatMessageFacadeService, times(1)).createChatMessage((CreateChatMessageRequest) any());
        verify(agentResumeService, never()).resume(any());
    }

    @Test
    void rejectMarksWaitingGenerationTaskFailed() {
        String approvalId = "approval-reject-1";
        String sessionId = "session-4";
        String genId = "gen-waiting-4";

        ToolGovernanceService.ApprovalRecord record = new ToolGovernanceService.ApprovalRecord(
                approvalId, sessionId, "dangerousTool", false, genId
        );
        when(toolGovernanceService.decide(approvalId, false)).thenReturn(record);
        when(resourceAccessService.requireOwnedChatSession(sessionId))
                .thenReturn(ChatSession.builder().id(sessionId).agentId("agent-1").ownerId("user-1").build());

        GenerationTask waitingTask = new GenerationTask(
                genId, null, "user-1", "alice", "USER", "req-4", "fp-4",
                "agent-1", sessionId, "msg-4", "input", GenerationTask.Status.WAITING_APPROVAL,
                1, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(generationTaskStore.findExecutionTask(genId)).thenReturn(Optional.of(waitingTask));

        ApiResponse<Void> response = controller.reject(approvalId);
        assertThat(response.getCode()).isEqualTo(200);

        verify(generationTaskStore, times(1)).markFailed(eq(genId), eq("worker-1"), eq(1L), eq("审批已被拒绝: dangerousTool"));
        verify(chatMessageFacadeService, times(1)).createChatMessage((CreateChatMessageRequest) any());
    }
}
