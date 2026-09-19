package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

/**
 * Agent 生成任务的持久化快照。inputContent 只用于内部恢复，不通过 API 返回。
 */
public record GenerationTask(
        String id,
        String parentGenerationId,
        String userId,
        String username,
        String role,
        String requestId,
        String requestFingerprint,
        String agentId,
        String sessionId,
        String userMessageId,
        String inputContent,
        Status status,
        int attemptCount,
        String lastError,
        LocalDateTime lastDispatchedAt,
        LocalDateTime heartbeatAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public boolean isRetryable() {
        return status == Status.FAILED;
    }
}

