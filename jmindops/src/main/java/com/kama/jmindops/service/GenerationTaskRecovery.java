package com.kama.jmindops.service;

import com.kama.jmindops.event.ChatEvent;
import com.kama.jmindops.message.SseMessage;
import com.kama.jmindops.model.entity.GenerationTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class GenerationTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(GenerationTaskRecovery.class);

    private final GenerationTaskStore generationTaskStore;
    private final ChatGenerationCoordinator chatGenerationCoordinator;
    private final ApplicationEventPublisher publisher;
    private final SseService sseService;
    private final Duration pendingRedispatchAfter;
    private final Duration runningTimeout;
    private final int batchSize;

    public GenerationTaskRecovery(
            GenerationTaskStore generationTaskStore,
            ChatGenerationCoordinator chatGenerationCoordinator,
            ApplicationEventPublisher publisher,
            SseService sseService,
            @Value("${app.generation.pending-redispatch-after-seconds:15}") long pendingRedispatchAfterSeconds,
            @Value("${app.generation.running-timeout-seconds:3600}") long runningTimeoutSeconds,
            @Value("${app.generation.recovery-batch-size:20}") int batchSize
    ) {
        this.generationTaskStore = generationTaskStore;
        this.chatGenerationCoordinator = chatGenerationCoordinator;
        this.publisher = publisher;
        this.sseService = sseService;
        this.pendingRedispatchAfter = Duration.ofSeconds(Math.max(5, pendingRedispatchAfterSeconds));
        this.runningTimeout = Duration.ofSeconds(Math.max(60, runningTimeoutSeconds));
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${app.generation.recovery-initial-delay-ms:15000}",
            fixedDelayString = "${app.generation.recovery-fixed-delay-ms:15000}"
    )
    public void recover() {
        heartbeatLocallyOwnedTasks();
        recoverPendingTasks();
        failStaleRunningTasks();
    }

    private void heartbeatLocallyOwnedTasks() {
        Map<String, String> running = chatGenerationCoordinator.runningGenerationsSnapshot();
        if (running == null || running.isEmpty()) {
            return;
        }
        running.values().forEach(generationTaskStore::touchHeartbeat);
    }

    private void recoverPendingTasks() {
        List<GenerationTask> tasks = generationTaskStore.findRecoverablePending(pendingRedispatchAfter, batchSize);
        for (GenerationTask task : tasks) {
            try {
                if (!chatGenerationCoordinator.restoreReservation(task.sessionId(), task.id())) {
                    continue;
                }
                if (!generationTaskStore.markDispatched(task.id())) {
                    chatGenerationCoordinator.release(task.sessionId(), task.id());
                    continue;
                }
                publisher.publishEvent(new ChatEvent(
                        task.agentId(), task.sessionId(), task.inputContent(), task.id()));
                log.info("Redispatched pending generation: sessionId={}, generationId={}",
                        task.sessionId(), task.id());
            } catch (Exception exception) {
                log.error("Failed to redispatch pending generation: generationId={}", task.id(), exception);
                chatGenerationCoordinator.release(task.sessionId(), task.id());
            }
        }
    }

    private void failStaleRunningTasks() {
        List<GenerationTask> timedOut = generationTaskStore.failStaleRunning(runningTimeout, batchSize);
        for (GenerationTask task : timedOut) {
            chatGenerationCoordinator.release(task.sessionId(), task.id());
            sseService.send(task.sessionId(), SseMessage.builder()
                    .type(SseMessage.Type.AI_ERROR)
                    .payload(SseMessage.Payload.builder()
                            .statusText("任务执行超时，可安全重试")
                            .done(true)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .generationId(task.id())
                            .build())
                    .build());
            log.warn("Marked stale generation as FAILED: sessionId={}, generationId={}",
                    task.sessionId(), task.id());
        }
    }
}
