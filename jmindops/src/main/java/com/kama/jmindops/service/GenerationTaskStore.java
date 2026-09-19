package com.kama.jmindops.service;

import com.kama.jmindops.exception.StaleGenerationLeaseException;
import com.kama.jmindops.model.entity.GenerationTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class GenerationTaskStore {
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final Pattern SENSITIVE_ERROR_VALUE = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|authorization)\\s*[=:]\\s*([^\\s,;]+)"
    );

    private static final String SELECT_COLUMNS = """
            SELECT gt.id, gt.parent_generation_id, gt.user_id, au.username, au.role,
                   gt.request_id, gt.request_fingerprint, gt.agent_id, gt.session_id,
                   gt.user_message_id, gt.input_content, gt.status, gt.attempt_count,
                   gt.last_error, gt.last_dispatched_at, gt.heartbeat_at, gt.started_at,
                   gt.completed_at, gt.created_at, gt.updated_at,
                   gt.worker_id, gt.lease_version
            FROM generation_task gt
            JOIN app_user au ON au.id = gt.user_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public GenerationTaskStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Serializes concurrent requests that carry the same user-scoped idempotency key.
     * The transaction-scoped PostgreSQL advisory lock closes the check-then-insert race;
     * the unique constraint remains the final invariant.
     */
    public void lockIdempotencyKey(String userId, String requestId) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (resultSet, rowNum) -> Boolean.TRUE,
                userId + ":" + requestId
        );
    }

    public void createPending(
            String generationId,
            String parentGenerationId,
            String userId,
            String requestId,
            String requestFingerprint,
            String agentId,
            String sessionId,
            String userMessageId,
            String inputContent
    ) {
        jdbcTemplate.update("""
                        INSERT INTO generation_task (
                            id, parent_generation_id, user_id, request_id, request_fingerprint,
                            agent_id, session_id, user_message_id, input_content, status,
                            last_dispatched_at, created_at, updated_at
                        ) VALUES (
                            CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?,
                            CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 'PENDING',
                            NOW(), NOW(), NOW()
                        )
                        """,
                generationId, parentGenerationId, userId, requestId, requestFingerprint,
                agentId, sessionId, userMessageId, inputContent);
    }

    public Optional<GenerationTask> findByUserAndRequestId(String userId, String requestId) {
        return queryOne(SELECT_COLUMNS + " WHERE gt.user_id = CAST(? AS uuid) AND gt.request_id = CAST(? AS uuid)",
                userId, requestId);
    }

    public Optional<GenerationTask> findOwnedById(String generationId, String userId) {
        return queryOne(SELECT_COLUMNS + " WHERE gt.id = CAST(? AS uuid) AND gt.user_id = CAST(? AS uuid)",
                generationId, userId);
    }

    public Optional<GenerationTask> findExecutionTask(String generationId) {
        return queryOne(SELECT_COLUMNS + " WHERE gt.id = CAST(? AS uuid)", generationId);
    }

    public Optional<GenerationTask> findWaitingApprovalForSession(String sessionId) {
        return queryOne(SELECT_COLUMNS + " WHERE gt.session_id = CAST(? AS uuid) AND gt.status = 'WAITING_APPROVAL' ORDER BY gt.created_at DESC LIMIT 1",
                sessionId);
    }

    public long claimForExecution(String generationId, String workerId) {
        List<Long> versions = jdbcTemplate.query("""
                UPDATE generation_task
                SET status = 'RUNNING',
                    attempt_count = attempt_count + 1,
                    worker_id = ?,
                    lease_version = lease_version + 1,
                    started_at = COALESCE(started_at, NOW()),
                    heartbeat_at = NOW(),
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'PENDING'
                RETURNING lease_version
                """, (rs, rowNum) -> rs.getLong("lease_version"), workerId, generationId);
        if (versions.isEmpty()) {
            throw new IllegalStateException("Task cannot be claimed for execution: generationId=" + generationId);
        }
        return versions.get(0);
    }

    public boolean markRunning(String generationId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'RUNNING', attempt_count = attempt_count + 1,
                    worker_id = ?, lease_version = lease_version + 1,
                    started_at = COALESCE(started_at, NOW()), heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'PENDING'
                """, workerId, generationId) == 1;
    }

    public boolean markRunning(String generationId) {
        return jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'RUNNING', attempt_count = attempt_count + 1,
                    worker_id = COALESCE(worker_id, 'default-worker'),
                    lease_version = CASE WHEN lease_version = 0 THEN 1 ELSE lease_version END,
                    started_at = COALESCE(started_at, NOW()), heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'PENDING'
                """, generationId) == 1;
    }

    public long claimForResume(String generationId, String workerId) {
        List<Long> versions = jdbcTemplate.query("""
                UPDATE generation_task
                SET status = 'RUNNING',
                    worker_id = ?,
                    lease_version = lease_version + 1,
                    heartbeat_at = NOW(),
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status IN ('RUNNING', 'WAITING_APPROVAL')
                RETURNING lease_version
                """, (rs, rowNum) -> rs.getLong("lease_version"), workerId, generationId);
        if (versions.isEmpty()) {
            throw new IllegalStateException("Task cannot be claimed for resume: generationId=" + generationId);
        }
        return versions.get(0);
    }

    public void touchHeartbeat(String generationId) {
        jdbcTemplate.update("""
                UPDATE generation_task SET heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                """, generationId);
    }

    public boolean touchHeartbeat(String generationId, String workerId, long leaseVersion) {
        return jdbcTemplate.update("""
                UPDATE generation_task SET heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                  AND (worker_id = ? OR worker_id IS NULL)
                  AND lease_version = ?
                """, generationId, workerId, leaseVersion) == 1;
    }

    public boolean markSucceeded(String generationId) {
        return jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'SUCCEEDED', completed_at = COALESCE(completed_at, NOW()), heartbeat_at = NOW(),
                    last_error = NULL, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('RUNNING', 'SUCCEEDED')
                """, generationId) == 1;
    }

    public boolean markSucceeded(String generationId, String workerId, long leaseVersion) {
        int updated = jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'SUCCEEDED', completed_at = COALESCE(completed_at, NOW()), heartbeat_at = NOW(),
                    last_error = NULL, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('RUNNING', 'SUCCEEDED')
                  AND (worker_id = ? OR worker_id IS NULL)
                  AND lease_version = ?
                """, generationId, workerId, leaseVersion);
        if (updated == 0) {
            throw new StaleGenerationLeaseException("Mark succeeded rejected by fencing: generationId="
                    + generationId + ", workerId=" + workerId + ", leaseVersion=" + leaseVersion);
        }
        return true;
    }

    public boolean markWaitingApproval(String generationId, String workerId, long leaseVersion) {
        int updated = jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'WAITING_APPROVAL', heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('RUNNING', 'WAITING_APPROVAL')
                  AND (worker_id = ? OR worker_id IS NULL)
                  AND lease_version = ?
                """, generationId, workerId, leaseVersion);
        if (updated == 0) {
            throw new StaleGenerationLeaseException("Mark waiting approval rejected by fencing: generationId="
                    + generationId + ", workerId=" + workerId + ", leaseVersion=" + leaseVersion);
        }
        return true;
    }

    public boolean markFailed(String generationId, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', completed_at = NOW(), heartbeat_at = NOW(),
                    last_error = ?, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')
                """, sanitizeError(errorMessage), generationId) == 1;
    }

    public boolean markFailed(String generationId, String workerId, long leaseVersion, String errorMessage) {
        return jdbcTemplate.update("""
                UPDATE generation_task
                SET status = 'FAILED', completed_at = NOW(), heartbeat_at = NOW(),
                    last_error = ?, updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL')
                  AND (worker_id = ? OR worker_id IS NULL)
                  AND (lease_version = ? OR lease_version = 0)
                """, sanitizeError(errorMessage), generationId, workerId, leaseVersion) == 1;
    }

    public boolean markDispatched(String generationId) {
        return jdbcTemplate.update("""
                UPDATE generation_task SET last_dispatched_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'PENDING'
                """, generationId) == 1;
    }

    public List<GenerationTask> findRecoverablePending(Duration minimumAge, int limit) {
        return jdbcTemplate.query(SELECT_COLUMNS + """
                        WHERE gt.status = 'PENDING'
                          AND gt.last_dispatched_at < NOW() - (? * INTERVAL '1 second')
                        ORDER BY gt.last_dispatched_at ASC
                        LIMIT ?
                        """, ROW_MAPPER, minimumAge.toSeconds(), limit);
    }

    public List<GenerationTask> failStaleRunning(Duration timeout, int limit) {
        return jdbcTemplate.query("""
                WITH stale AS (
                    SELECT id
                    FROM generation_task
                    WHERE status = 'RUNNING'
                      AND COALESCE(heartbeat_at, started_at, updated_at) < NOW() - (? * INTERVAL '1 second')
                    ORDER BY COALESCE(heartbeat_at, started_at, updated_at) ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ), updated AS (
                    UPDATE generation_task gt
                    SET status = 'FAILED', completed_at = NOW(),
                        last_error = '任务心跳超时，可安全重试', updated_at = NOW()
                    FROM stale
                    WHERE gt.id = stale.id AND gt.status = 'RUNNING'
                    RETURNING gt.*
                )
                SELECT updated.id, updated.parent_generation_id, updated.user_id, au.username, au.role,
                       updated.request_id, updated.request_fingerprint, updated.agent_id, updated.session_id,
                       updated.user_message_id, updated.input_content, updated.status, updated.attempt_count,
                       updated.last_error, updated.last_dispatched_at, updated.heartbeat_at, updated.started_at,
                       updated.completed_at, updated.created_at, updated.updated_at,
                       updated.worker_id, updated.lease_version
                FROM updated
                JOIN app_user au ON au.id = updated.user_id
                """, ROW_MAPPER, timeout.toSeconds(), limit);
    }

    private Optional<GenerationTask> queryOne(String sql, Object... args) {
        List<GenerationTask> tasks = jdbcTemplate.query(sql, ROW_MAPPER, args);
        return tasks.stream().findFirst();
    }

    public static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "Agent 执行失败";
        }
        String singleLine = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        singleLine = SENSITIVE_ERROR_VALUE.matcher(singleLine).replaceAll("$1=******");
        return singleLine.length() <= MAX_ERROR_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_ERROR_LENGTH);
    }

    private static LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static final RowMapper<GenerationTask> ROW_MAPPER = (rs, rowNum) -> new GenerationTask(
            rs.getString("id"),
            rs.getString("parent_generation_id"),
            rs.getString("user_id"),
            rs.getString("username"),
            rs.getString("role"),
            rs.getString("request_id"),
            rs.getString("request_fingerprint"),
            rs.getString("agent_id"),
            rs.getString("session_id"),
            rs.getString("user_message_id"),
            rs.getString("input_content"),
            GenerationTask.Status.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            rs.getString("last_error"),
            timestamp(rs, "last_dispatched_at"),
            timestamp(rs, "heartbeat_at"),
            timestamp(rs, "started_at"),
            timestamp(rs, "completed_at"),
            timestamp(rs, "created_at"),
            timestamp(rs, "updated_at"),
            rs.getString("worker_id"),
            rs.getLong("lease_version")
    );
}
