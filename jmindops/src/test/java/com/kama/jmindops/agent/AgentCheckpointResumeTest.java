package com.kama.jmindops.agent;

import com.kama.jmindops.agent.checkpoint.CheckpointPayload;
import com.kama.jmindops.agent.tools.ToolIdempotencyResolver;
import com.kama.jmindops.controller.ToolApprovalController;
import com.kama.jmindops.exception.NonIdempotentToolReplayException;
import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.governance.ToolGovernanceService;
import com.kama.jmindops.model.entity.AgentCheckpoint;
import com.kama.jmindops.model.entity.AgentToolExecution;
import com.kama.jmindops.model.entity.ChatSession;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.AgentCheckpointStore;
import com.kama.jmindops.service.AgentResumeService;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.GenerationTaskRecovery;
import com.kama.jmindops.service.GenerationTaskStore;
import com.kama.jmindops.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCheckpointResumeTest {

    /**
     * 1. 场景 A：模型已返回 tool call，但在执行工具前进程崩溃。
     * 恢复后直接基于 checkpoint 执行恢复的工具调用，不再重复调用模型。
     */
    @Test
    void resumeAfterModelBeforeToolExecution() {
        String generationId = "gen-resume-after-model";
        String sessionId = "session-1";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback toolA = mockToolCallback("toolA", "result-A");

        // Model generated tool call in step 1, but process crashed before tool execution
        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-1", "function", "toolA", "{\"arg\":\"val\"}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Execute toolA"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint modelOutputCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(modelOutputCheckpoint));
        when(checkpointStore.findToolExecution(generationId, "tc-1")).thenReturn(Optional.empty());

        ChatClient chatClient = mockChatClientWithFinalAnswer("Final answer after tool execution");

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(toolA),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Execute toolA",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Execute toolA"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "worker-1",
                1L
        );

        runtime.run();

        // 1. Tool A was executed with the restored arguments
        verify(toolA, times(1)).call(eq("{\"arg\":\"val\"}"), any(org.springframework.ai.chat.model.ToolContext.class));
        // 2. ChatClient prompt was called ONLY once (for step 2 to generate the final answer), NOT twice (step 1 was resumed without duplicate model call)
        verify(chatClient, times(1)).prompt(any(Prompt.class));
        // 3. Agent completed successfully
        assertThat(runtime.getAgentState()).isEqualTo(AgentState.FINISHED);
        // 4. Recovery reconciliation was called
        verify(checkpointStore).reconcileExecutingToolsOnRecovery(generationId);
    }

    /**
     * 2. 场景 B：模型返回多个 tool calls，tool 1 已执行成功并落库账本，tool 2 执行前崩溃。
     * 恢复后只执行 tool 2，复用 tool 1 的结果，不重复产生副作用。
     */
    @Test
    void resumeDoesNotRepeatSucceededTool() {
        String generationId = "gen-no-repeat-succeeded";
        String sessionId = "session-2";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback toolA = mockToolCallback("toolA", "result-A-fresh");
        ToolCallback toolB = mockToolCallback("toolB", "result-B-fresh");

        // Two tool calls were planned. toolA already succeeded in ledger before crash!
        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-1", "function", "toolA", "{\"key\":\"a\"}"),
                new AssistantMessage.ToolCall("tc-2", "function", "toolB", "{\"key\":\"b\"}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Run A and B"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint modelOutputCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(modelOutputCheckpoint));
        // toolA already SUCCEEDED with persisted result in execution ledger
        AgentToolExecution toolAExecution = new AgentToolExecution(
                UUID.randomUUID().toString(), generationId, 1, "tc-1", "toolA",
                "{\"key\":\"a\"}", AgentToolExecution.Status.SUCCEEDED, "persisted-result-A",
                null, true, null, null, null, null
        );
        when(checkpointStore.findToolExecution(generationId, "tc-1")).thenReturn(Optional.of(toolAExecution));
        when(checkpointStore.findToolExecution(generationId, "tc-2")).thenReturn(Optional.empty());

        ChatClient chatClient = mockChatClientWithFinalAnswer("Finished");

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(toolA, toolB),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Run A and B",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Run A and B"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "worker-1",
                1L
        );

        runtime.run();

        // toolA was NOT invoked again (reused from ledger)
        verify(toolA, never()).call(anyString(), any());
        verify(toolA, never()).call(anyString());
        // toolB WAS invoked once
        verify(toolB, times(1)).call(eq("{\"key\":\"b\"}"), any(org.springframework.ai.chat.model.ToolContext.class));
        assertThat(runtime.getAgentState()).isEqualTo(AgentState.FINISHED);
    }

    /**
     * 3. 场景 C：WAITING_APPROVAL 状态的任务在进程重启 / 扫描器执行时不会被当做超时失败。
     */
    @Test
    void waitingApprovalSurvivesRestart() {
        GenerationTaskStore store = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SseService sseService = mock(SseService.class);

        GenerationTaskRecovery recovery = new GenerationTaskRecovery(
                store, coordinator, publisher, sseService, 15, 600, 20
        );

        // failStaleRunning only targets RUNNING tasks, never WAITING_APPROVAL
        when(store.failStaleRunning(Duration.ofSeconds(600), 20)).thenReturn(List.of());
        when(store.findRecoverablePending(Duration.ofSeconds(15), 20)).thenReturn(List.of());
        when(coordinator.runningGenerationsSnapshot()).thenReturn(java.util.Map.of());

        recovery.recover();

        // No tasks were marked failed or redispatched
        verify(store, never()).markFailed(anyString(), anyString(), anyLong(), anyString());
        verify(store, never()).markDispatched(anyString());
    }

    /**
     * 4. 场景 D：人工审批后，直接在原 generation_id 上继续执行，直至成功完成，状态更新为 SUCCEEDED。
     */
    @Test
    void approvalResumesSameGeneration() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        String approvalId = UUID.randomUUID().toString();
        String generationId = "gen-same-approval-resume";
        String sessionId = "session-hitl";

        ToolGovernanceService toolGovernanceService = mock(ToolGovernanceService.class);
        ResourceAccessService resourceAccessService = mock(ResourceAccessService.class);
        GenerationTaskStore generationTaskStore = mock(GenerationTaskStore.class);
        AgentResumeService agentResumeService = mock(AgentResumeService.class);

        ToolGovernanceService.ApprovalRecord approvalRecord = new ToolGovernanceService.ApprovalRecord(
                approvalId, sessionId, "secureTool", true, generationId
        );
        when(toolGovernanceService.decide(approvalId, true)).thenReturn(approvalRecord);

        ChatSession session = new ChatSession();
        session.setAgentId("agent-1");
        when(resourceAccessService.requireOwnedChatSession(sessionId)).thenReturn(session);

        GenerationTask waitingTask = new GenerationTask(
                generationId, null, "user-1", "alice", "USER", "req-1", "fp",
                "agent-1", sessionId, "msg-1", "do secure op", GenerationTask.Status.WAITING_APPROVAL,
                0, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(generationTaskStore.findExecutionTask(generationId)).thenReturn(Optional.of(waitingTask));

        ToolApprovalController controller = new ToolApprovalController(
                toolGovernanceService, null, resourceAccessService, generationTaskStore, agentResumeService
        );

        try {
            // Activate transaction synchronization (simulating Spring @Transactional environment)
            TransactionSynchronizationManager.initSynchronization();
            controller.approve(approvalId);

            // Before commit: resume MUST NOT have been called
            verify(agentResumeService, never()).resume(anyString());

            // Simulate database transaction COMMIT
            TransactionSynchronizationUtils.triggerAfterCommit();

            // After commit: verify async resume is invoked with the exact same generationId without sleep
            verify(agentResumeService, timeout(5000).times(1)).resume(generationId);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    /**
     * 5. 场景 E：旧 Worker 发生脑裂或网络分区，尝试使用旧租约提交 Checkpoint / 状态时被防护击退。
     */
    @Test
    void staleLeaseCannotCommit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AgentCheckpointStore checkpointStore = new AgentCheckpointStore(jdbcTemplate);

        String generationId = "gen-stale-lease";
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                2L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                "[]",
                "{}",
                null,
                null,
                null,
                null
        );

        // Simulate DB update returning 0 rows because lease_version has already incremented
        when(jdbcTemplate.update(
                anyString(),
                eq(checkpoint.stepNo()),
                eq(checkpoint.checkpointVersion()),
                eq("RUNNING"),
                eq("RUNNING"),
                eq(generationId),
                eq("RUNNING"),
                eq("RUNNING"),
                eq("stale-worker"),
                eq(1L)
        )).thenReturn(0);

        assertThatThrownBy(() -> checkpointStore.saveCheckpoint(checkpoint, "stale-worker", 1L))
                .isInstanceOf(StaleGenerationLeaseException.class)
                .hasMessageContaining("Generation checkpoint commit rejected by fencing");
    }

    /**
     * 6. 场景 F：外部非幂等工具在 EXECUTING 期间崩溃，恢复扫描器将其标记为 UNKNOWN，
     * 重启后禁止自动重放，直接抛出 NonIdempotentToolReplayException，防止重复扣款/写副作用。
     */
    @Test
    void nonIdempotentUnknownToolIsNotAutomaticallyReplayed() {
        String generationId = "gen-non-idempotent-unknown";
        String sessionId = "session-f";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback paymentTool = mockToolCallback("externalPayment", "{\"status\":\"paid\"}");
        when(idempotencyResolver.isIdempotent(eq("externalPayment"), any())).thenReturn(false);

        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-pay", "function", "externalPayment", "{\"amount\":100}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Pay 100"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint modelOutputCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(modelOutputCheckpoint));
        // Tool execution in UNKNOWN status and NOT idempotent
        AgentToolExecution toolExecution = new AgentToolExecution(
                UUID.randomUUID().toString(), generationId, 1, "tc-pay", "externalPayment",
                "{\"amount\":100}", AgentToolExecution.Status.UNKNOWN, null,
                null, false, null, null, null, null
        );
        when(checkpointStore.findToolExecution(generationId, "tc-pay")).thenReturn(Optional.of(toolExecution));

        ChatClient chatClient = mockChatClientWithFinalAnswer("Finished");

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(paymentTool),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Pay 100",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Pay 100"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "worker-1",
                1L
        );

        // Must throw NonIdempotentToolReplayException
        assertThatThrownBy(runtime::run)
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(NonIdempotentToolReplayException.class);

        // Payment tool callback must NEVER be invoked!
        verify(paymentTool, never()).call(anyString());
        verify(paymentTool, never()).call(anyString(), any());
    }

    /**
     * 7. 核心约束：不要持久化隐藏 Chain-of-Thought (CoT)，只保存可见内容。
     */
    @Test
    void chainOfThoughtIsNotPersistedInCheckpoint() {
        String privateCoT = "<think>Step 1: check user balance. Step 2: process transaction secret internal thinking.</think>";
        String visibleText = "您的账户余额为 1000 元。";
        AssistantMessage message = new AssistantMessage(privateCoT + visibleText);

        String serialized = CheckpointPayload.serializeMessages(List.of(message));

        // Must NOT contain private CoT
        assertThat(serialized).doesNotContain("secret internal thinking");
        assertThat(serialized).doesNotContain("<think>");
        assertThat(serialized).doesNotContain("</think>");
        // Must contain visible text
        assertThat(serialized).contains("您的账户余额为 1000 元。");

        // When deserialized, only visible text is restored
        List<Message> restored = CheckpointPayload.deserializeMessages(serialized);
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getText()).isEqualTo(visibleText);
    }

    /**
     * 8. 场景 H：AgentResumeService 端到端恢复执行：从 WAITING_APPROVAL 状态认领并递增租约，
     * 执行至完成，并通过带租约防护的 markSucceeded 更新为 SUCCEEDED。
     */
    @Test
    void endToEndAgentResumeServiceSucceedsAndFences() {
        String generationId = "gen-resume-e2e";
        String sessionId = "session-e2e";

        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        SseService sseService = mock(SseService.class);
        JMindOps runtime = mock(JMindOps.class);

        GenerationTask waitingTask = new GenerationTask(
                generationId, null, "user-1", "alice", "USER", "req-1", "fp",
                "agent-1", sessionId, "msg-1", "input", GenerationTask.Status.WAITING_APPROVAL,
                0, null, null, null, null, null, null, null, "old-worker", 1L
        );
        when(taskStore.findExecutionTask(generationId)).thenReturn(Optional.of(waitingTask));
        when(coordinator.claimForResume(eq(sessionId), eq(generationId))).thenReturn(true);
        when(taskStore.claimForResume(eq(generationId), anyString())).thenReturn(2L);
        when(factory.createForResume(eq(waitingTask), anyString(), eq(2L))).thenReturn(runtime);
        when(runtime.getAgentState()).thenReturn(AgentState.FINISHED);
        when(taskStore.markSucceeded(eq(generationId), anyString(), eq(2L))).thenReturn(true);

        AgentResumeService resumeService = new AgentResumeService(taskStore, coordinator, factory, sseService);
        resumeService.resume(generationId);

        verify(runtime).run();
        verify(taskStore).markSucceeded(eq(generationId), anyString(), eq(2L));
        verify(sseService).send(eq(sessionId), any());
        verify(coordinator).release(eq(sessionId), eq(generationId));
    }

    /**
     * 9. 场景 I：当 Worker 发生租约冲突 (StaleGenerationLeaseException) 时，
     * 绝不调用 markFailed 篡改新 Worker 的状态。
     */
    @Test
    void staleLeaseWorkerCannotMarkTaskFailed() {
        String generationId = "gen-zombie-worker";
        String sessionId = "session-zombie";

        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        SseService sseService = mock(SseService.class);
        JMindOps runtime = mock(JMindOps.class);

        GenerationTask waitingTask = new GenerationTask(
                generationId, null, "user-1", "alice", "USER", "req-1", "fp",
                "agent-1", sessionId, "msg-1", "input", GenerationTask.Status.WAITING_APPROVAL,
                0, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(taskStore.findExecutionTask(generationId)).thenReturn(Optional.of(waitingTask));
        when(coordinator.claimForResume(eq(sessionId), eq(generationId))).thenReturn(true);
        when(taskStore.claimForResume(eq(generationId), anyString())).thenReturn(2L);
        when(factory.createForResume(eq(waitingTask), anyString(), eq(2L))).thenReturn(runtime);

        // Simulate stale lease exception thrown during runtime execution
        org.mockito.Mockito.doThrow(new StaleGenerationLeaseException("Fenced out by higher lease version"))
                .when(runtime).run();

        AgentResumeService resumeService = new AgentResumeService(taskStore, coordinator, factory, sseService);

        assertThatThrownBy(() -> resumeService.resume(generationId))
                .isInstanceOf(StaleGenerationLeaseException.class);

        // markFailed must NEVER be called by a fenced worker!
        verify(taskStore, never()).markFailed(anyString(), anyString());
        verify(taskStore, never()).markFailed(anyString(), anyString(), anyLong(), anyString());
        verify(coordinator).release(eq(sessionId), eq(generationId));
    }

    /**
     * 10. 场景 J：恢复时工具调用仍处于 WAITING_APPROVAL 状态且尚未获批，
     * Agent 保持等待，不重复发起工具调用。
     */
    @Test
    void unapprovedToolRemainsInWaitingApproval() {
        String generationId = "gen-unapproved-wait";
        String sessionId = "session-wait";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback dangerousTool = mockToolCallback("dangerousTool", "done");

        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-danger", "function", "dangerousTool", "{\"action\":\"delete\"}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Delete resource"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint waitingCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.WAITING_APPROVAL,
                GenerationTask.Status.WAITING_APPROVAL,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(waitingCheckpoint));
        AgentToolExecution toolExecution = new AgentToolExecution(
                UUID.randomUUID().toString(), generationId, 1, "tc-danger", "dangerousTool",
                "{\"action\":\"delete\"}", AgentToolExecution.Status.WAITING_APPROVAL, null,
                null, false, null, null, null, null
        );
        when(checkpointStore.findToolExecution(generationId, "tc-danger")).thenReturn(Optional.of(toolExecution));
        // Tool has NOT been approved yet
        when(checkpointStore.isToolApprovalGranted(generationId, "tc-danger", "dangerousTool")).thenReturn(false);

        ChatClient chatClient = mock(ChatClient.class);

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(dangerousTool),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Delete resource",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Delete resource"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "worker-1",
                1L
        );

        runtime.run();

        // Agent must be in WAITING_APPROVAL state
        assertThat(runtime.getAgentState()).isEqualTo(AgentState.WAITING_APPROVAL);
        // Dangerous tool must NEVER be invoked!
        verify(dangerousTool, never()).call(anyString());
        verify(dangerousTool, never()).call(anyString(), any());
    }

    /**
     * 11. 场景 K：非幂等工具在执行时抛出异常（网络超时/未知副作用），账本记录为 UNKNOWN 状态而非 FAILED。
     */
    @Test
    void nonIdempotentToolExceptionRecordedAsUnknown() {
        String generationId = "gen-tool-exception-unknown";
        String sessionId = "session-k";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback riskyTool = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("riskyTool");
        when(riskyTool.getToolDefinition()).thenReturn(definition);
        when(riskyTool.call(any(), any())).thenThrow(new RuntimeException("Connection reset by peer"));
        when(idempotencyResolver.isIdempotent(eq("riskyTool"), any())).thenReturn(false);

        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-risk", "function", "riskyTool", "{\"data\":1}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Run risky tool"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint modelOutputCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(modelOutputCheckpoint));
        when(checkpointStore.findToolExecution(generationId, "tc-risk")).thenReturn(Optional.empty());

        ChatClient chatClient = mock(ChatClient.class);

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(riskyTool),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Run risky tool",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Run risky tool"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "worker-1",
                1L
        );

        assertThatThrownBy(runtime::run)
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Connection reset by peer");

        // Status MUST be recorded as UNKNOWN for non-idempotent tool!
        verify(checkpointStore).markToolUnknown(eq(generationId), eq("tc-risk"), eq("Connection reset by peer"), eq("worker-1"), eq(1L));
        verify(checkpointStore, never()).markToolFailed(eq(generationId), eq("tc-risk"), anyString(), anyString(), anyLong());
    }

    /**
     * 13. 场景 M：在执行工具前强制 lease fencing 检查发现租约过期，直接抛出 StaleGenerationLeaseException，
     * 工具回调绝不被执行。
     */
    @Test
    void staleWorkerCannotInvokeToolBeforeCallback() {
        String generationId = "gen-stale-tool-inv";
        String sessionId = "session-stale-tool";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);

        ToolCallback riskyTool = mockToolCallback("riskyTool", "result");

        List<AssistantMessage.ToolCall> pendingCalls = List.of(
                new AssistantMessage.ToolCall("tc-risky", "function", "riskyTool", "{\"data\":1}")
        );
        String pendingCallsJson = CheckpointPayload.serializeToolCalls(pendingCalls);
        List<Message> initialMessages = List.of(new UserMessage("Run risky tool"));
        String messagesPayload = CheckpointPayload.serializeMessages(initialMessages);
        CheckpointPayload.CheckpointRuntimeState runtimeState = new CheckpointPayload.CheckpointRuntimeState(
                RoutingDecision.CHAT.name(), List.of(), 0, null, false, 0, 100L, 1, 0
        );
        String runtimeStateJson = CheckpointPayload.serializeRuntimeState(runtimeState);

        AgentCheckpoint modelOutputCheckpoint = new AgentCheckpoint(
                UUID.randomUUID().toString(),
                generationId,
                1,
                1L,
                AgentCheckpoint.Stage.MODEL_OUTPUT,
                GenerationTask.Status.RUNNING,
                messagesPayload,
                runtimeStateJson,
                pendingCallsJson,
                null,
                null,
                null
        );

        when(checkpointStore.findLatestCheckpoint(generationId)).thenReturn(Optional.of(modelOutputCheckpoint));
        when(checkpointStore.findToolExecution(generationId, "tc-risky")).thenReturn(Optional.empty());

        // Lease check fails right before tool execution
        org.mockito.Mockito.doThrow(new StaleGenerationLeaseException("Fenced out before tool execution"))
                .when(checkpointStore).assertActiveLease(eq(generationId), eq("stale-worker"), eq(1L));

        ChatClient chatClient = mock(ChatClient.class);

        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                initialMessages,
                List.of(riskyTool),
                List.of(),
                sessionId,
                generationId,
                mock(ApplicationEventPublisher.class),
                null,
                RoutingDecision.CHAT,
                "Run risky tool",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "Run risky tool"),
                Duration.ofSeconds(60),
                checkpointStore,
                idempotencyResolver,
                "stale-worker",
                1L
        );

        assertThatThrownBy(runtime::run)
                .isInstanceOf(StaleGenerationLeaseException.class)
                .hasMessageContaining("Fenced out before tool execution");

        // Tool callback was NEVER invoked
        verify(riskyTool, never()).call(any());
        verify(riskyTool, never()).call(any(), any());
    }

    /**
     * 12. 场景 L：在 Agent 执行过程中 Checkpoint 提交因租约过期被 Fencing 拒绝。
     * 必须直接抛出 StaleGenerationLeaseException，不包装为通用异常，且绝不能覆写持久化状态为 FAILED 或发送 AI_ERROR SSE。
     */
    @Test
    void staleLeaseExceptionDuringExecutionPreservesFencingWithoutMarkingFailed() {
        String generationId = "gen-stale-lease-test";
        String sessionId = "session-stale";
        AgentCheckpointStore checkpointStore = mock(AgentCheckpointStore.class);
        ToolIdempotencyResolver idempotencyResolver = mock(ToolIdempotencyResolver.class);
        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        SseService sseService = mock(SseService.class);

        GenerationTask task = new GenerationTask(
                generationId, null, "user-1", "alice", "USER", "req-1", "fp-1",
                "agent-1", sessionId, "msg-1", "input", GenerationTask.Status.RUNNING,
                1, null, null, null, null, null, null, null, "worker-1", 1L
        );
        when(taskStore.findExecutionTask(generationId)).thenReturn(Optional.of(task));
        when(coordinator.claimForResume(sessionId, generationId)).thenReturn(true);
        when(taskStore.claimForResume(eq(generationId), anyString())).thenReturn(1L);

        ChatClient chatClient = mockChatClientWithFinalAnswer("answer");
        JMindOps runtime = new JMindOps(
                "agent-1", "test-agent", "", "", chatClient,
                20, 0.0, 0.9,
                List.of(new UserMessage("test")),
                List.of(), List.of(),
                sessionId, generationId,
                mock(ApplicationEventPublisher.class),
                null, RoutingDecision.CHAT, "test",
                AgentExecutionPolicy.plan(RoutingDecision.CHAT, "test"),
                Duration.ofSeconds(60),
                checkpointStore, idempotencyResolver,
                "worker-1", 1L
        );

        // Checkpoint store throws StaleGenerationLeaseException during saveCheckpoint
        org.mockito.Mockito.doThrow(new StaleGenerationLeaseException("Lease expired"))
                .when(checkpointStore).saveCheckpoint(any(), anyString(), anyLong());

        when(factory.createForResume(any(), anyString(), anyLong())).thenReturn(runtime);

        AgentResumeService resumeService = new AgentResumeService(
                taskStore, coordinator, factory, sseService);

        // Calling resume should fail due to StaleGenerationLeaseException
        assertThatThrownBy(() -> resumeService.resume(generationId))
                .isInstanceOf(StaleGenerationLeaseException.class);

        // Fencing guarantee: task must NEVER be marked FAILED, and terminal AI_ERROR must NEVER be sent!
        verify(taskStore, never()).markFailed(anyString(), anyString());
        verify(taskStore, never()).markFailed(anyString(), anyString(), anyLong(), anyString());
        verify(sseService, never()).send(eq(sessionId), any());
    }

    /**
     * 13. 场景 M：审批检查严格基于 generation_id 与 tool_call_id，禁止仅凭 tool_name 跨 generation / toolCall 误命中。
     */
    @Test
    void approvalDoesNotCrossMatchAcrossGenerationsOrToolCalls() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AgentCheckpointStore checkpointStore = new AgentCheckpointStore(jdbcTemplate);

        String gen1 = "11111111-1111-1111-1111-111111111111";
        String gen2 = "22222222-2222-2222-2222-222222222222";
        String call1 = "call-1";
        String call2 = "call-2";
        String toolName = "sensitiveTool";

        // When queried for gen1 + call1: return approval-id-1
        when(jdbcTemplate.queryForList(
                anyString(),
                eq(String.class),
                eq(gen1), eq(call1), eq(toolName)
        )).thenReturn(List.of("approval-id-1"));

        // When queried for gen2 + call1: return empty list
        when(jdbcTemplate.queryForList(
                anyString(),
                eq(String.class),
                eq(gen2), eq(call1), eq(toolName)
        )).thenReturn(List.of());

        // When queried for gen1 + call2: return empty list
        when(jdbcTemplate.queryForList(
                anyString(),
                eq(String.class),
                eq(gen1), eq(call2), eq(toolName)
        )).thenReturn(List.of());

        assertThat(checkpointStore.isToolApprovalGranted(gen1, call1, toolName)).isTrue();
        assertThat(checkpointStore.isToolApprovalGranted(gen2, call1, toolName)).isFalse();
        assertThat(checkpointStore.isToolApprovalGranted(gen1, call2, toolName)).isFalse();
    }

    private ChatClient mockChatClientWithFinalAnswer(String answer) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);

        AssistantMessage assistantMessage = new AssistantMessage(answer);
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(assistantMessage)));
        when(streamSpec.chatResponse()).thenReturn(reactor.core.publisher.Flux.just(chatResponse));

        return chatClient;
    }

    private ToolCallback mockToolCallback(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(any())).thenReturn(result);
        when(callback.call(any(), any())).thenReturn(result);
        return callback;
    }
}
