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
        LocalDateTime updatedAt,
        String workerId,
        long leaseVersion
) {
    public enum Status {
        PENDING,
        RUNNING,
        WAITING_APPROVAL,
        SUCCEEDED,
        FAILED
    }

    public GenerationTask(
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
        this(id, parentGenerationId, userId, username, role, requestId, requestFingerprint,
                agentId, sessionId, userMessageId, inputContent, status, attemptCount, lastError,
                lastDispatchedAt, heartbeatAt, startedAt, completedAt, createdAt, updatedAt,
                null, 0L);
    }

    public boolean isRetryable() {
        return status == Status.FAILED;
    }
}
