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
        when(store.failStaleRunning(Duration.ofSeconds(600), 20)).thenReturn(List.of());
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
        when(store.failStaleRunning(Duration.ofSeconds(600), 20)).thenReturn(List.of());

        recovery.recover();

        verify(store).touchHeartbeat("generation-1");
    }

    private GenerationTask pendingTask() {
        return new GenerationTask(
                "generation-1", null, "user-1", "alice", "USER", "request-1", "fingerprint",
                "agent-1", "session-1", "message-1", "hello", GenerationTask.Status.PENDING,
                0, null, null, null, null, null, null, null);
    }
}
