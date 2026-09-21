package com.kama.jmindops.service;

import com.kama.jmindops.model.response.AgentTraceResponse;
import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.governance.ToolApprovalSignal;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentTraceStore {
    private final JdbcTemplate jdbcTemplate;

    public AgentTraceStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void assertActiveLease(String generationId, String workerId, long leaseVersion) {
        if (generationId == null) {
            throw new IllegalArgumentException("generationId must not be null");
        }
        if (workerId == null || leaseVersion <= 0) {
            return;
        }
        List<Integer> list = jdbcTemplate.query("""
                SELECT 1 FROM generation_task
                WHERE id = CAST(? AS uuid)
                  AND status IN ('RUNNING', 'WAITING_APPROVAL')
                  AND worker_id = ?
                  AND lease_version = ?
                """, (rs, rowNum) -> 1, generationId, workerId, leaseVersion);
        if (list.isEmpty()) {
            throw new StaleGenerationLeaseException("Trace mutation rejected by fencing: generationId="
                    + generationId + ", workerId=" + workerId + ", leaseVersion=" + leaseVersion);
        }
    }

    public void recordRouting(String generationId, String routingDecision, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET routing_decision = ?, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                  AND worker_id = ? AND lease_version = ?
                """, routingDecision, generationId, workerId, leaseVersion);
    }

    /**
     * @deprecated Use {@link #recordRouting(String, String, String, long)} with fencing instead.
     */
    @Deprecated
    public void recordRouting(String generationId, String routingDecision) {
        jdbcTemplate.update("""
                UPDATE generation_task
                SET routing_decision = ?, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                """, routingDecision, generationId);
    }

    @Transactional
    public String startStep(String generationId, int stepNo, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        String stepId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_step_trace (id, generation_id, step_no, status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'THINKING')
                ON CONFLICT (generation_id, step_no) DO UPDATE
                SET updated_at = NOW()
                RETURNING id
                """, String.class, UUID.randomUUID().toString(), generationId, stepNo);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET current_step = GREATEST(current_step, ?), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                  AND worker_id = ? AND lease_version = ?
                """, stepNo, generationId, workerId, leaseVersion);
        return stepId;
    }

    /**
     * @deprecated Use {@link #startStep(String, int, String, long)} with fencing instead.
     */
    @Deprecated
    @Transactional
    public String startStep(String generationId, int stepNo) {
        String stepId = jdbcTemplate.queryForObject("""
                INSERT INTO agent_step_trace (id, generation_id, step_no, status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'THINKING')
                ON CONFLICT (generation_id, step_no) DO UPDATE
                SET updated_at = NOW()
                RETURNING id
                """, String.class, UUID.randomUUID().toString(), generationId, stepNo);
        jdbcTemplate.update("""
                UPDATE generation_task
                SET current_step = GREATEST(current_step, ?), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                """, stepNo, generationId);
        return stepId;
    }

    @Transactional
    public long completeThinking(
            String generationId,
            String stepId,
            AssistantMessage message,
            Usage usage,
            Long latencyMs,
            String modelName,
            String workerId,
            long leaseVersion
    ) {
        assertActiveLease(generationId, workerId, leaseVersion);
        List<AssistantMessage.ToolCall> toolCalls = message == null || message.getToolCalls() == null
                ? List.of()
                : message.getToolCalls();
        String output = message == null || message.getText() == null ? "" : message.getText();
        TokenUsage tokenUsage = extractUsage(usage);
        boolean completed = toolCalls.isEmpty();

        jdbcTemplate.update("""
                UPDATE agent_step_trace
                SET status = ?, model_name = ?, has_tool_calls = ?, tool_call_count = ?,
                    prompt_tokens = ?, completion_tokens = ?, total_tokens = ?, model_latency_ms = ?,
                    output_length = ?, output_hash = ?, completed_at = CASE WHEN ? THEN NOW() ELSE NULL END,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                """,
                completed ? "COMPLETED" : "THINKING", modelName, !toolCalls.isEmpty(), toolCalls.size(),
                tokenUsage.promptTokens(), tokenUsage.completionTokens(), tokenUsage.totalTokens(), latencyMs,
                output.length(), sha256(output), completed, stepId, generationId);

        for (int index = 0; index < toolCalls.size(); index++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            String toolCallId = normalizeToolCallId(toolCall.id(), stepId, index + 1);
            String arguments = toolCall.arguments() == null ? "" : toolCall.arguments();
            jdbcTemplate.update("""
                    INSERT INTO tool_invocation_trace (
                        id, generation_id, step_trace_id, tool_call_id, invocation_order,
                        tool_name, arguments_length, arguments_hash, status
                    ) VALUES (
                        CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, 'PREPARED'
                    )
                    ON CONFLICT (generation_id, tool_call_id) DO NOTHING
                    """,
                    UUID.randomUUID().toString(), generationId, stepId, toolCallId, index + 1,
                    toolCall.name(), arguments.length(), sha256(arguments));
        }

        if (workerId != null && leaseVersion > 0) {
            jdbcTemplate.update("""
                    UPDATE generation_task
                    SET cumulative_tokens = cumulative_tokens + ?, updated_at = NOW()
                    WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                      AND worker_id = ? AND lease_version = ?
                    """, tokenUsage.totalTokens(), generationId, workerId, leaseVersion);
        } else {
            jdbcTemplate.update("""
                    UPDATE generation_task
                    SET cumulative_tokens = cumulative_tokens + ?, updated_at = NOW()
                    WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                    """, tokenUsage.totalTokens(), generationId);
        }
        return tokenUsage.totalTokens();
    }

    /**
     * @deprecated Use {@link #completeThinking(String, String, AssistantMessage, Usage, Long, String, String, long)} with fencing instead.
     */
    @Deprecated
    @Transactional
    public long completeThinking(
            String generationId,
            String stepId,
            AssistantMessage message,
            Usage usage,
            Long latencyMs,
            String modelName
    ) {
        return completeThinking(generationId, stepId, message, usage, latencyMs, modelName, null, 0L);
    }

    @Transactional
    public void markToolsRunning(String generationId, String stepId, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolsRunning(generationId, stepId);
    }

    /**
     * @deprecated Use {@link #markToolsRunning(String, String, String, long)} with fencing instead.
     */
    @Deprecated
    @Transactional
    public void markToolsRunning(String generationId, String stepId) {
        jdbcTemplate.update("""
                UPDATE agent_step_trace
                SET status = 'EXECUTING_TOOLS', updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                  AND status = 'THINKING'
                """, stepId, generationId);
        jdbcTemplate.update("""
                UPDATE tool_invocation_trace
                SET status = 'RUNNING', started_at = NOW(), updated_at = NOW()
                WHERE step_trace_id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                  AND status = 'PREPARED'
                """, stepId, generationId);
    }

    @Transactional
    public void completeTools(
            String generationId,
            String stepId,
            ToolResponseMessage responseMessage,
            long latencyMs,
            String workerId,
            long leaseVersion
    ) {
        assertActiveLease(generationId, workerId, leaseVersion);
        completeTools(generationId, stepId, responseMessage, latencyMs);
    }

    /**
     * @deprecated Use {@link #completeTools(String, String, ToolResponseMessage, long, String, long)} with fencing instead.
     */
    @Deprecated
    @Transactional
    public void completeTools(
            String generationId,
            String stepId,
            ToolResponseMessage responseMessage,
            long latencyMs
    ) {
        List<ToolResponseMessage.ToolResponse> responses = responseMessage == null
                ? List.of()
                : responseMessage.getResponses();
        for (ToolResponseMessage.ToolResponse response : responses) {
            String result = response.responseData() == null ? "" : response.responseData();
            String status = ToolApprovalSignal.isWaitingResponse(result) ? "WAITING_APPROVAL" : "SUCCEEDED";
            int updated = jdbcTemplate.update("""
                    UPDATE tool_invocation_trace
                    SET status = ?, result_length = ?, result_hash = ?, latency_ms = ?,
                        completed_at = NOW(), updated_at = NOW()
                    WHERE generation_id = CAST(? AS uuid) AND step_trace_id = CAST(? AS uuid)
                      AND tool_call_id = ? AND status IN ('PREPARED', 'RUNNING')
                    """, status, result.length(), sha256(result), latencyMs,
                    generationId, stepId, response.id());
            if (updated == 0) {
                jdbcTemplate.update("""
                        UPDATE tool_invocation_trace
                        SET status = ?, result_length = ?, result_hash = ?, latency_ms = ?,
                            completed_at = NOW(), updated_at = NOW()
                        WHERE id = (
                            SELECT id FROM tool_invocation_trace
                            WHERE generation_id = CAST(? AS uuid) AND step_trace_id = CAST(? AS uuid)
                              AND tool_name = ? AND status IN ('PREPARED', 'RUNNING')
                            ORDER BY invocation_order LIMIT 1
                        )
                        """, status, result.length(), sha256(result), latencyMs,
                        generationId, stepId, response.name());
            }
        }
        int unresolved = jdbcTemplate.update("""
                UPDATE tool_invocation_trace
                SET status = 'UNKNOWN', last_error = '工具响应缺失或无法关联',
                    completed_at = NOW(), updated_at = NOW()
                WHERE step_trace_id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                  AND status IN ('PREPARED', 'RUNNING')
                """, stepId, generationId);
        jdbcTemplate.update("""
                UPDATE agent_step_trace
                SET status = ?, last_error = ?, completed_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                """,
                unresolved == 0 ? "COMPLETED" : "UNKNOWN",
                unresolved == 0 ? null : "存在无法关联结果的工具调用",
                stepId, generationId);
    }

    @Transactional
    public void failStep(String generationId, String stepId, String errorMessage, boolean toolOutcomeUnknown, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        failStep(generationId, stepId, errorMessage, toolOutcomeUnknown);
    }

    /**
     * @deprecated Use {@link #failStep(String, String, String, boolean, String, long)} with fencing instead.
     */
    @Deprecated
    @Transactional
    public void failStep(String generationId, String stepId, String errorMessage, boolean toolOutcomeUnknown) {
        if (stepId == null) {
            return;
        }
        String sanitized = GenerationTaskStore.sanitizeError(errorMessage);
        String status = toolOutcomeUnknown ? "UNKNOWN" : "FAILED";
        jdbcTemplate.update("""
                UPDATE agent_step_trace
                SET status = ?, last_error = ?, completed_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                """, status, sanitized, stepId, generationId);
        jdbcTemplate.update("""
                UPDATE tool_invocation_trace
                SET status = CASE WHEN status = 'RUNNING' THEN 'UNKNOWN' ELSE 'FAILED' END,
                    last_error = ?, completed_at = NOW(), updated_at = NOW()
                WHERE step_trace_id = CAST(? AS uuid) AND generation_id = CAST(? AS uuid)
                  AND status IN ('PREPARED', 'RUNNING')
                """, sanitized, stepId, generationId);
    }

    public Optional<AgentTraceResponse> findTrace(String generationId) {
        List<RunRow> runs = jdbcTemplate.query("""
                SELECT id, routing_decision, current_step, cumulative_tokens, checkpoint_version
                FROM generation_task
                WHERE id = CAST(? AS uuid)
                """, (rs, rowNum) -> new RunRow(
                rs.getString("id"),
                rs.getString("routing_decision"),
                rs.getInt("current_step"),
                rs.getLong("cumulative_tokens"),
                rs.getLong("checkpoint_version")
        ), generationId);
        if (runs.isEmpty()) {
            return Optional.empty();
        }

        Map<String, List<AgentTraceResponse.ToolInvocation>> invocationsByStep = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT id, step_trace_id, tool_call_id, invocation_order, tool_name,
                       arguments_length, arguments_hash, status, result_length, result_hash,
                       latency_ms, last_error, started_at, completed_at
                FROM tool_invocation_trace
                WHERE generation_id = CAST(? AS uuid)
                ORDER BY step_trace_id, invocation_order
                """, rs -> {
            String traceId = rs.getString("step_trace_id");
            invocationsByStep.computeIfAbsent(traceId, ignored -> new ArrayList<>())
                    .add(mapInvocation(rs));
        }, generationId);

        List<AgentTraceResponse.Step> steps = jdbcTemplate.query("""
                SELECT id, step_no, status, model_name, has_tool_calls, tool_call_count,
                       prompt_tokens, completion_tokens, total_tokens, model_latency_ms,
                       output_length, output_hash, last_error, started_at, completed_at
                FROM agent_step_trace
                WHERE generation_id = CAST(? AS uuid)
                ORDER BY step_no
                """, (rs, rowNum) -> {
            String stepId = rs.getString("id");
            return new AgentTraceResponse.Step(
                    stepId,
                    rs.getInt("step_no"),
                    rs.getString("status"),
                    rs.getString("model_name"),
                    rs.getBoolean("has_tool_calls"),
                    rs.getInt("tool_call_count"),
                    rs.getLong("prompt_tokens"),
                    rs.getLong("completion_tokens"),
                    rs.getLong("total_tokens"),
                    nullableLong(rs, "model_latency_ms"),
                    rs.getInt("output_length"),
                    rs.getString("output_hash"),
                    rs.getString("last_error"),
                    timestamp(rs, "started_at"),
                    timestamp(rs, "completed_at"),
                    List.copyOf(invocationsByStep.getOrDefault(stepId, List.of()))
            );
        }, generationId);

        RunRow run = runs.get(0);
        return Optional.of(new AgentTraceResponse(
                run.generationId(), run.routingDecision(), run.currentStep(),
                run.cumulativeTokens(), run.checkpointVersion(), List.copyOf(steps)));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static AgentTraceResponse.ToolInvocation mapInvocation(ResultSet rs) throws SQLException {
        return new AgentTraceResponse.ToolInvocation(
                rs.getString("id"),
                rs.getString("tool_call_id"),
                rs.getInt("invocation_order"),
                rs.getString("tool_name"),
                rs.getInt("arguments_length"),
                rs.getString("arguments_hash"),
                rs.getString("status"),
                nullableInteger(rs, "result_length"),
                rs.getString("result_hash"),
                nullableLong(rs, "latency_ms"),
                rs.getString("last_error"),
                timestamp(rs, "started_at"),
                timestamp(rs, "completed_at")
        );
    }

    private static TokenUsage extractUsage(Usage usage) {
        if (usage == null) {
            return new TokenUsage(0, 0, 0);
        }
        long prompt = invokeNumber(usage, "getPromptTokens");
        long completion = invokeNumber(usage, "getGenerationTokens");
        if (completion == 0) {
            completion = invokeNumber(usage, "getCompletionTokens");
        }
        long total = invokeNumber(usage, "getTotalTokens");
        if (total == 0) {
            total = prompt + completion;
        }
        return new TokenUsage(prompt, completion, total);
    }

    private static long invokeNumber(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value instanceof Number number ? Math.max(number.longValue(), 0) : 0;
        } catch (ReflectiveOperationException ignored) {
            return 0;
        }
    }

    private static String normalizeToolCallId(String toolCallId, String stepId, int invocationOrder) {
        return toolCallId == null || toolCallId.isBlank()
                ? stepId + ":" + invocationOrder
                : toolCallId;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private record RunRow(
            String generationId,
            String routingDecision,
            int currentStep,
            long cumulativeTokens,
            long checkpointVersion
    ) {
    }

    private record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {
    }
}
