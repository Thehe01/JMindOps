package com.kama.jmindops.model.entity;

import java.time.LocalDateTime;

public record AgentCheckpoint(
        String id,
        String generationId,
        int stepNo,
        long checkpointVersion,
        Stage stage,
        GenerationTask.Status status,
        String messagesPayload,
        String runtimeState,
        String pendingToolCalls,
        String toolResults,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public enum Stage {
        INITIAL,
        MODEL_OUTPUT,
        WAITING_APPROVAL,
        STEP_COMPLETED
    }
}
