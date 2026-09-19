package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

public record AgentToolExecution(
        String id,
        String generationId,
        int stepNo,
        String toolCallId,
        String toolName,
        String arguments,
        Status status,
        String result,
        String errorMessage,
        boolean idempotent,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public enum Status {
        PREPARED,
        EXECUTING,
        SUCCEEDED,
        WAITING_APPROVAL,
        FAILED,
        UNKNOWN
    }
}
