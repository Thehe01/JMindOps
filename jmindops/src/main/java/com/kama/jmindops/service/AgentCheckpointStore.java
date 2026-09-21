package com.kama.jmindops.service;

import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.model.entity.AgentCheckpoint;
import com.kama.jmindops.model.entity.AgentToolExecution;
import com.kama.jmindops.model.entity.GenerationTask;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AgentCheckpointStore {

    private final JdbcTemplate jdbcTemplate;

    public AgentCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AgentCheckpoint> CHECKPOINT_ROW_MAPPER = (rs, rowNum) -> new AgentCheckpoint(
            rs.getString("id"),
            rs.getString("generation_id"),
            rs.getInt("step_no"),
            rs.getLong("checkpoint_version"),
            AgentCheckpoint.Stage.valueOf(rs.getString("stage")),
            GenerationTask.Status.valueOf(rs.getString("status")),
            rs.getString("messages_payload"),
            rs.getString("runtime_state"),
            rs.getString("pending_tool_calls"),
            rs.getString("tool_results"),
            timestamp(rs, "created_at"),
            timestamp(rs, "updated_at")
    );

    private static final RowMapper<AgentToolExecution> TOOL_EXECUTION_ROW_MAPPER = (rs, rowNum) -> new AgentToolExecution(
            rs.getString("id"),
            rs.getString("generation_id"),
            rs.getInt("step_no"),
            rs.getString("tool_call_id"),
            rs.getString("tool_name"),
            rs.getString("arguments"),
            AgentToolExecution.Status.valueOf(rs.getString("status")),
            rs.getString("result"),
            rs.getString("error_message"),
            rs.getBoolean("is_idempotent"),
            timestamp(rs, "started_at"),
            timestamp(rs, "completed_at"),
            timestamp(rs, "created_at"),
            timestamp(rs, "updated_at")
    );

    @Transactional
    public void saveCheckpoint(AgentCheckpoint checkpoint, String workerId, long leaseVersion) {
        String targetStatus = checkpoint.status().name();
        // Fencing check: verify that this worker still holds the valid lease on generation_task
        int updated = jdbcTemplate.update("""
                UPDATE generation_task
                SET current_step = GREATEST(current_step, ?),
                    checkpoint_version = ?,
                    status = ?,
                    completed_at = CASE WHEN ? = 'SUCCEEDED' THEN COALESCE(completed_at, NOW()) ELSE completed_at END,
                    heartbeat_at = NOW(),
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND (status = 'RUNNING' OR (status = 'WAITING_APPROVAL' AND ? = 'WAITING_APPROVAL') OR (status = 'SUCCEEDED' AND ? = 'SUCCEEDED'))
                  AND worker_id = ?
                  AND lease_version = ?
                """,
                checkpoint.stepNo(),
                checkpoint.checkpointVersion(),
                targetStatus,
                targetStatus,
                checkpoint.generationId(),
                targetStatus,
                targetStatus,
                workerId,
                leaseVersion
        );

        if (updated == 0) {
            throw new StaleGenerationLeaseException("Generation checkpoint commit rejected by fencing: generationId="
                    + checkpoint.generationId() + ", workerId=" + workerId + ", leaseVersion=" + leaseVersion);
        }

        jdbcTemplate.update("""
                INSERT INTO agent_checkpoint (
                    id, generation_id, step_no, checkpoint_version, stage, status,
                    messages_payload, runtime_state, pending_tool_calls, tool_results,
                    created_at, updated_at
                ) VALUES (
                    CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    NOW(), NOW()
                )
                ON CONFLICT (generation_id, checkpoint_version) DO UPDATE
                SET stage = EXCLUDED.stage,
                    status = EXCLUDED.status,
                    messages_payload = EXCLUDED.messages_payload,
                    runtime_state = EXCLUDED.runtime_state,
                    pending_tool_calls = EXCLUDED.pending_tool_calls,
                    tool_results = EXCLUDED.tool_results,
                    updated_at = NOW()
                """,
                checkpoint.id() != null ? checkpoint.id() : UUID.randomUUID().toString(),
                checkpoint.generationId(),
                checkpoint.stepNo(),
                checkpoint.checkpointVersion(),
                checkpoint.stage().name(),
                targetStatus,
                checkpoint.messagesPayload(),
                checkpoint.runtimeState(),
                checkpoint.pendingToolCalls(),
                checkpoint.toolResults()
        );
    }

    public Optional<AgentCheckpoint> findLatestCheckpoint(String generationId) {
        List<AgentCheckpoint> list = jdbcTemplate.query("""
                SELECT id, generation_id, step_no, checkpoint_version, stage, status,
                       messages_payload, runtime_state, pending_tool_calls, tool_results,
                       created_at, updated_at
                FROM agent_checkpoint
                WHERE generation_id = CAST(? AS uuid)
                ORDER BY checkpoint_version DESC, step_no DESC
                LIMIT 1
                """, CHECKPOINT_ROW_MAPPER, generationId);
        return list.stream().findFirst();
    }

    /**
     * 强租约校验：在工具执行或账本变更前，校验当前 worker 是否依然合法持有该 generation 的租约。
     */
    public void assertActiveLease(String generationId, String workerId, long leaseVersion) {
        if (generationId == null) {
            throw new IllegalArgumentException("generationId must not be null");
        }
        List<Integer> list = jdbcTemplate.query("""
                SELECT 1 FROM generation_task
                WHERE id = CAST(? AS uuid)
                  AND status IN ('RUNNING', 'WAITING_APPROVAL')
                  AND worker_id = ?
                  AND lease_version = ?
                """, (rs, rowNum) -> 1, generationId, workerId, leaseVersion);
        if (list.isEmpty()) {
            throw new StaleGenerationLeaseException("Tool ledger mutation rejected by fencing: generationId="
                    + generationId + ", workerId=" + workerId + ", leaseVersion=" + leaseVersion);
        }
    }

    @Transactional
    public void recordPreparedToolCall(
            String generationId,
            int stepNo,
            AssistantMessage.ToolCall toolCall,
            boolean isIdempotent,
            String workerId,
            long leaseVersion
    ) {
        assertActiveLease(generationId, workerId, leaseVersion);
        recordPreparedToolCall(generationId, stepNo, toolCall, isIdempotent);
    }

    public void recordToolPrepared(
            String generationId,
            int stepNo,
            AssistantMessage.ToolCall toolCall,
            boolean isIdempotent,
            String workerId,
            long leaseVersion
    ) {
        recordPreparedToolCall(generationId, stepNo, toolCall, isIdempotent, workerId, leaseVersion);
    }

    public void recordPreparedToolCall(
            String generationId,
            int stepNo,
            AssistantMessage.ToolCall toolCall,
            boolean isIdempotent
    ) {
        jdbcTemplate.update("""
                INSERT INTO agent_tool_execution (
                    id, generation_id, step_no, tool_call_id, tool_name,
                    arguments, status, is_idempotent, created_at, updated_at
                ) VALUES (
                    CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                    ?, 'PREPARED', ?, NOW(), NOW()
                )
                ON CONFLICT (generation_id, tool_call_id) DO NOTHING
                """,
                UUID.randomUUID().toString(),
                generationId,
                stepNo,
                toolCall.id(),
                toolCall.name(),
                toolCall.arguments() == null ? "" : toolCall.arguments(),
                isIdempotent
        );
    }

    public Optional<AgentToolExecution> findToolExecution(String generationId, String toolCallId) {
        List<AgentToolExecution> list = jdbcTemplate.query("""
                SELECT id, generation_id, step_no, tool_call_id, tool_name, arguments,
                       status, result, error_message, is_idempotent, started_at, completed_at,
                       created_at, updated_at
                FROM agent_tool_execution
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                """, TOOL_EXECUTION_ROW_MAPPER, generationId, toolCallId);
        return list.stream().findFirst();
    }

    public List<AgentToolExecution> findToolExecutionsForStep(String generationId, int stepNo) {
        return jdbcTemplate.query("""
                SELECT id, generation_id, step_no, tool_call_id, tool_name, arguments,
                       status, result, error_message, is_idempotent, started_at, completed_at,
                       created_at, updated_at
                FROM agent_tool_execution
                WHERE generation_id = CAST(? AS uuid) AND step_no = ?
                ORDER BY created_at ASC
                """, TOOL_EXECUTION_ROW_MAPPER, generationId, stepNo);
    }

    public void recordToolExecuting(String generationId, String toolCallId, String workerId, long leaseVersion) {
        markToolExecuting(generationId, toolCallId, workerId, leaseVersion);
    }

    @Transactional
    public void markToolExecuting(String generationId, String toolCallId, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolExecuting(generationId, toolCallId);
    }

    public void markToolExecuting(String generationId, String toolCallId) {
        jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'EXECUTING', started_at = NOW(), updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                  AND status IN ('PREPARED', 'EXECUTING', 'WAITING_APPROVAL')
                """, generationId, toolCallId);
    }

    public boolean isToolApprovalGranted(String generationId, String toolCallId, String toolName) {
        List<String> list = jdbcTemplate.queryForList("""
                SELECT a.id::text FROM tool_approval a
                WHERE a.status = 'APPROVED'
                  AND a.expires_at > NOW()
                  AND a.generation_id = CAST(? AS uuid)
                  AND (a.tool_call_id = ? OR (a.tool_call_id IS NULL AND a.tool_name = ?))
                LIMIT 1
                """, String.class, generationId, toolCallId, toolName);
        return !list.isEmpty();
    }

    public void recordToolSucceeded(String generationId, String toolCallId, String result, String workerId, long leaseVersion) {
        markToolSucceeded(generationId, toolCallId, result, workerId, leaseVersion);
    }

    @Transactional
    public void markToolSucceeded(String generationId, String toolCallId, String result, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolSucceeded(generationId, toolCallId, result);
    }

    public void markToolSucceeded(String generationId, String toolCallId, String result) {
        jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'SUCCEEDED', result = ?, completed_at = NOW(), updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                """, result, generationId, toolCallId);
    }

    public void recordToolWaitingApproval(String generationId, String toolCallId, String result, String workerId, long leaseVersion) {
        markToolWaitingApproval(generationId, toolCallId, result, workerId, leaseVersion);
    }

    @Transactional
    public void markToolWaitingApproval(String generationId, String toolCallId, String result, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolWaitingApproval(generationId, toolCallId, result);
    }

    public void markToolWaitingApproval(String generationId, String toolCallId, String result) {
        jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'WAITING_APPROVAL', result = ?, updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                """, result, generationId, toolCallId);
    }

    public void recordToolUnknown(String generationId, String toolCallId, String error, String workerId, long leaseVersion) {
        markToolUnknown(generationId, toolCallId, error, workerId, leaseVersion);
    }

    @Transactional
    public void markToolUnknown(String generationId, String toolCallId, String error, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolUnknown(generationId, toolCallId, error);
    }

    public void markToolUnknown(String generationId, String toolCallId, String error) {
        jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'UNKNOWN', error_message = ?, updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                """, error, generationId, toolCallId);
    }

    public void recordToolFailed(String generationId, String toolCallId, String error, String workerId, long leaseVersion) {
        markToolFailed(generationId, toolCallId, error, workerId, leaseVersion);
    }

    @Transactional
    public void markToolFailed(String generationId, String toolCallId, String error, String workerId, long leaseVersion) {
        assertActiveLease(generationId, workerId, leaseVersion);
        markToolFailed(generationId, toolCallId, error);
    }

    public void markToolFailed(String generationId, String toolCallId, String error) {
        jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'FAILED', error_message = ?, completed_at = NOW(), updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND tool_call_id = ?
                """, error, generationId, toolCallId);
    }

    /**
     * 当进程崩溃或重启恢复时，将所有留在 EXECUTING 状态的工具调用标记为 UNKNOWN（因为副作用可能已产生但未确认提交）。
     */
    public int reconcileExecutingToolsOnRecovery(String generationId) {
        return jdbcTemplate.update("""
                UPDATE agent_tool_execution
                SET status = 'UNKNOWN',
                    error_message = 'Process crashed during tool execution; side effect in-doubt',
                    updated_at = NOW()
                WHERE generation_id = CAST(? AS uuid) AND status = 'EXECUTING'
                """, generationId);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
