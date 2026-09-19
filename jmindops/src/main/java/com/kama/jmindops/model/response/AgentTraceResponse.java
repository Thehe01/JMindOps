package com.kama.jmindops.model.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Privacy-preserving execution trace. Raw prompts, tool arguments and tool results are deliberately excluded.
 */
public record AgentTraceResponse(
        String generationId,
        String routingDecision,
        int currentStep,
        long cumulativeTokens,
        long checkpointVersion,
        List<Step> steps
) {
    public record Step(
            String stepId,
            int stepNo,
            String status,
            String modelName,
            boolean hasToolCalls,
            int toolCallCount,
            long promptTokens,
            long completionTokens,
            long totalTokens,
            Long modelLatencyMs,
            int outputLength,
            String outputHash,
            String lastError,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            List<ToolInvocation> toolInvocations
    ) {
    }

    public record ToolInvocation(
            String invocationId,
            String toolCallId,
            int invocationOrder,
            String toolName,
            int argumentsLength,
            String argumentsHash,
            String status,
            Integer resultLength,
            String resultHash,
            Long latencyMs,
            String lastError,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {
    }
}
