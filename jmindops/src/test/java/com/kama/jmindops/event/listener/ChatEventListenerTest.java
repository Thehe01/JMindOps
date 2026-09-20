package com.kama.jmindops.event.listener;

import com.kama.jmindops.agent.JMindOps;
import com.kama.jmindops.agent.JMindOpsFactory;
import com.kama.jmindops.agent.RouterAgent;
import com.kama.jmindops.agent.RoutingDecision;
import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.model.entity.GenerationTask;
import com.kama.jmindops.service.ChatGenerationCoordinator;
import com.kama.jmindops.service.GenerationTaskStore;
import com.kama.jmindops.service.AgentTraceStore;
import com.kama.jmindops.service.SseService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

class ChatEventListenerTest {

    @Test
    void duplicateEventDoesNotReleaseTheRunningGeneration() {
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        RouterAgent routerAgent = mock(RouterAgent.class);
        SseService sseService = mock(SseService.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        AgentTraceStore traceStore = mock(AgentTraceStore.class);
        ChatEventListener listener = new ChatEventListener(factory, routerAgent, sseService, coordinator, taskStore, traceStore);
        ChatEvent event = new ChatEvent("agent-1", "session-1", "hello", "generation-1");
        when(taskStore.findExecutionTask("generation-1")).thenReturn(Optional.of(pendingTask()));
        when(coordinator.claim("session-1", "generation-1")).thenReturn(false);

        listener.handle(event);

        verify(coordinator, never()).release("session-1", "generation-1");
        verifyNoInteractions(factory, routerAgent, sseService);
    }

    @Test
    void persistsLifecycleTransitionsForASuccessfulGeneration() {
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        RouterAgent routerAgent = mock(RouterAgent.class);
        SseService sseService = mock(SseService.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        AgentTraceStore traceStore = mock(AgentTraceStore.class);
        JMindOps runtime = mock(JMindOps.class);
        ChatEventListener listener = new ChatEventListener(factory, routerAgent, sseService, coordinator, taskStore, traceStore);
        ChatEvent event = new ChatEvent("untrusted-agent", "untrusted-session", "untrusted-input", "generation-1");

        when(taskStore.findExecutionTask("generation-1")).thenReturn(Optional.of(pendingTask()));
        when(coordinator.claim("session-1", "generation-1")).thenReturn(true);
        when(taskStore.claimForExecution(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        when(taskStore.touchHeartbeat(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);
        when(routerAgent.rewrite("session-1", "hello")).thenReturn("rewritten");
        when(routerAgent.route("rewritten")).thenReturn(RoutingDecision.CHAT);
        when(factory.create(
                org.mockito.ArgumentMatchers.eq("agent-1"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                org.mockito.ArgumentMatchers.eq(RoutingDecision.CHAT),
                org.mockito.ArgumentMatchers.eq("generation-1"),
                org.mockito.ArgumentMatchers.eq("rewritten"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(1L)
        )).thenReturn(runtime);
        when(taskStore.markSucceeded(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(true);

        listener.handle(event);

        verify(runtime).run();
        verify(taskStore).claimForExecution(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString());
        verify(taskStore).markSucceeded(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L));
        verify(taskStore, never()).markRunning(org.mockito.ArgumentMatchers.anyString());
        verify(taskStore, never()).markSucceeded(org.mockito.ArgumentMatchers.anyString());
        verify(coordinator).registerRunning(org.mockito.ArgumentMatchers.eq("session-1"), org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L));
        verify(traceStore).recordRouting("generation-1", "CHAT");
        verify(sseService).send(org.mockito.ArgumentMatchers.eq("session-1"), any());
        verify(coordinator).release("session-1", "generation-1");
    }

    @Test
    void staleHeartbeatThrowsExceptionAndSkipsMarkFailed() {
        JMindOpsFactory factory = mock(JMindOpsFactory.class);
        RouterAgent routerAgent = mock(RouterAgent.class);
        SseService sseService = mock(SseService.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        GenerationTaskStore taskStore = mock(GenerationTaskStore.class);
        AgentTraceStore traceStore = mock(AgentTraceStore.class);
        ChatEventListener listener = new ChatEventListener(factory, routerAgent, sseService, coordinator, taskStore, traceStore);
        ChatEvent event = new ChatEvent("agent-1", "session-1", "hello", "generation-1");

        when(taskStore.findExecutionTask("generation-1")).thenReturn(Optional.of(pendingTask()));
        when(coordinator.claim("session-1", "generation-1")).thenReturn(true);
        when(taskStore.claimForExecution(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        // Heartbeat rejected by fencing (lease taken over or bumped)
        when(taskStore.touchHeartbeat(org.mockito.ArgumentMatchers.eq("generation-1"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(1L))).thenReturn(false);
        when(routerAgent.rewrite("session-1", "hello")).thenReturn("rewritten");

        listener.handle(event);

        // markFailed must be skipped for stale worker!
        verify(taskStore, never()).markFailed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        verify(taskStore, never()).markFailed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(coordinator).release("session-1", "generation-1");
    }

    private GenerationTask pendingTask() {
        return new GenerationTask(
                "generation-1", null, "user-1", "alice", "USER", "request-1", "fingerprint",
                "agent-1", "session-1", "message-1", "hello", GenerationTask.Status.PENDING,
                0, null, null, null, null, null, null, null
        );
    }
}
