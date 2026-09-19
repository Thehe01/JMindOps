package com.kama.jmindops.model.response;

import com.kama.jmindops.model.entity.GenerationTask;

import java.time.LocalDateTime;

public record GenerationTaskResponse(
        String generationId,
        String parentGenerationId,
        String requestId,
        String agentId,
        String sessionId,
        String userMessageId,
        String status,
        int attemptCount,
        boolean retryable,
        String lastError,
        LocalDateTime lastDispatchedAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static GenerationTaskResponse from(GenerationTask task) {
        return new GenerationTaskResponse(
                task.id(),
                task.parentGenerationId(),
                task.requestId(),
                task.agentId(),
                task.sessionId(),
                task.userMessageId(),
                task.status().name(),
                task.attemptCount(),
                task.isRetryable(),
                task.lastError(),
                task.lastDispatchedAt(),
                task.startedAt(),
                task.completedAt(),
                task.createdAt(),
                task.updatedAt()
        );
    }
}

