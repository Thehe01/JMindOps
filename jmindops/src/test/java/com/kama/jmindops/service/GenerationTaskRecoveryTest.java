package com.kama.jmindops.service;

import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.model.entity.GenerationTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;

class GenerationTaskRecoveryTest {
    @Test
    void redispatchesAnExpiredPendingTaskAfterRestoringItsReservation() {
        GenerationTaskStore store = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SseService sseService = mock(SseService.class);
        GenerationTaskRecovery recovery = new GenerationTaskRecovery(
                store, coordinator, publisher, sseService, 15, 600, 20);
        GenerationTask task = pendingTask();
        when(coordinator.runningGenerationsSnapshot()).thenReturn(java.util.Map.of());
        when(store.findRecoverablePending(Duration.ofSeconds(15), 20)).thenReturn(List.of(task));
        when(store.claimStaleRunningForResume(anyString(), any(Duration.class), anyInt())).thenReturn(List.of());
        when(coordinator.restoreReservation("session-1", "generation-1")).thenReturn(true);
        when(store.markDispatched("generation-1")).thenReturn(true);

        recovery.recover();

        verify(store).markDispatched("generation-1");
        verify(publisher).publishEvent(any(ChatEvent.class));
    }

    @Test
    void heartbeatsOnlyGenerationsOwnedByThisInstance() {
        GenerationTaskStore store = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SseService sseService = mock(SseService.class);
        GenerationTaskRecovery recovery = new GenerationTaskRecovery(
                store, coordinator, publisher, sseService, 15, 600, 20);
        when(coordinator.runningGenerationsSnapshot()).thenReturn(
                java.util.Map.of("session-1", "generation-1"));
        when(store.findRecoverablePending(Duration.ofSeconds(15), 20)).thenReturn(List.of());
        when(store.claimStaleRunningForResume(anyString(), any(Duration.class), anyInt())).thenReturn(List.of());

        recovery.recover();

        verify(store).touchHeartbeat("generation-1");
    }

    @Test
    void claimsStaleRunningTasksAndTriggersAsyncResume() {
        GenerationTaskStore store = mock(GenerationTaskStore.class);
        ChatGenerationCoordinator coordinator = mock(ChatGenerationCoordinator.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        SseService sseService = mock(SseService.class);
        AgentResumeService agentResumeService = mock(AgentResumeService.class);

        GenerationTaskRecovery recovery = new GenerationTaskRecovery(
                store, coordinator, publisher, sseService, agentResumeService, 15, 600, 20);

        GenerationTask staleRunningTask = new GenerationTask(
                "gen-stale-1", null, "user-1", "alice", "USER", "request-stale", "fingerprint",
                "agent-1", "session-1", "message-1", "hello", GenerationTask.Status.RUNNING,
                1, null, null, null, null, null, null, null, "recovery-worker-1", 2L
        );

        when(coordinator.runningGenerationsSnapshot()).thenReturn(java.util.Map.of());
        when(store.findRecoverablePending(any(Duration.class), anyInt())).thenReturn(List.of());
        when(store.claimStaleRunningForResume(anyString(), eq(Duration.ofSeconds(600)), eq(20)))
                .thenReturn(List.of(staleRunningTask));

        recovery.recover();

        // Verify that resumeClaimed was triggered asynchronously
        verify(agentResumeService, timeout(1000).times(1)).resumeClaimed(staleRunningTask);
        // And never marked failed!
        verify(store, never()).failStaleRunning(any(), anyInt());
    }

    private GenerationTask pendingTask() {
        return new GenerationTask(
                "generation-1", null, "user-1", "alice", "USER", "request-1", "fingerprint",
                "agent-1", "session-1", "message-1", "hello", GenerationTask.Status.PENDING,
                0, null, null, null, null, null, null, null);
    }
}
