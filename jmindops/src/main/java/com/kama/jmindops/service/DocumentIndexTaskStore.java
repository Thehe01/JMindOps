package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class DocumentIndexTaskStore {
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final Pattern SENSITIVE_ERROR_VALUE = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key|authorization)\\s*[=:]\\s*([^\\s,;]+)"
    );

    private static final String SELECT_COLUMNS = """
            SELECT id, kb_id, document_id, index_version, status,
                   lease_version, cancel_requested,
                   retry_count, max_retries, next_retry_at, heartbeat_at,
                   worker_id, last_error, started_at, completed_at,
                   file_path, filename, filetype, file_size,
                   content_hash, source_key, index_fingerprint,
                   is_new_document, old_file_path,
                   chunk_count, reused_chunk_count, embedded_chunk_count,
                   created_at, updated_at
            FROM document_index_task
            """;

    private final JdbcTemplate jdbcTemplate;

    public DocumentIndexTaskStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void lockDocumentVersion(String documentId, int indexVersion) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (resultSet, rowNum) -> Boolean.TRUE,
                documentId + ":" + indexVersion
        );
    }

    public void lockDocument(String kbId, String sourceKey) {
        jdbcTemplate.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (resultSet, rowNum) -> Boolean.TRUE,
                kbId + ":" + sourceKey
        );
    }

    public void createTask(DocumentIndexTask task) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO document_index_task (
                        id, kb_id, document_id, index_version, status,
                        retry_count, max_retries, next_retry_at, heartbeat_at,
                        worker_id, last_error, started_at, completed_at,
                        file_path, filename, filetype, file_size,
                        content_hash, source_key, index_fingerprint,
                        is_new_document, old_file_path,
                        chunk_count, reused_chunk_count, embedded_chunk_count,
                        created_at, updated_at
                    ) VALUES (
                        CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?,
                        ?, ?, ?,
                        COALESCE(?, NOW()), COALESCE(?, NOW())
                    )
                    """,
                    task.getId(),
                    task.getKbId(),
                    task.getDocumentId(),
                    task.getIndexVersion(),
                    task.getStatus().name(),
                    task.getRetryCount() != null ? task.getRetryCount() : 0,
                    task.getMaxRetries() != null ? task.getMaxRetries() : 3,
                    task.getNextRetryAt(),
                    task.getHeartbeatAt(),
                    task.getWorkerId(),
                    sanitizeError(task.getLastError()),
                    task.getStartedAt(),
                    task.getCompletedAt(),
                    task.getFilePath(),
                    task.getFilename(),
                    task.getFiletype(),
                    task.getFileSize(),
                    task.getContentHash(),
                    task.getSourceKey(),
                    task.getIndexFingerprint(),
                    task.isNewDocument(),
                    task.getOldFilePath(),
                    task.getChunkCount() != null ? task.getChunkCount() : 0,
                    task.getReusedChunkCount() != null ? task.getReusedChunkCount() : 0,
                    task.getEmbeddedChunkCount() != null ? task.getEmbeddedChunkCount() : 0,
                    task.getCreatedAt(),
                    task.getUpdatedAt()
            );
        } catch (DuplicateKeyException e) {
            throw new BizException("重复创建文档索引任务: documentId=" + task.getDocumentId() + ", version=" + task.getIndexVersion());
        }
    }

    public Optional<DocumentIndexTask> claimNextTask(String workerId) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT t.id
                    FROM document_index_task t
                    WHERE (
                        t.status = 'PENDING'
                        OR (t.status = 'RETRY_WAIT' AND (next_retry_at IS NULL OR next_retry_at <= NOW()))
                    )
                    AND t.cancel_requested = FALSE
                    AND NOT EXISTS (
                        SELECT 1
                        FROM document_index_task earlier
                        WHERE earlier.document_id = t.document_id
                          AND earlier.index_version < t.index_version
                          AND earlier.status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                          AND earlier.cancel_requested = FALSE
                    )
                    ORDER BY t.created_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE document_index_task t
                SET status = 'RUNNING',
                    lease_version = t.lease_version + 1,
                    worker_id = ?,
                    heartbeat_at = NOW(),
                    started_at = COALESCE(t.started_at, NOW()),
                    updated_at = NOW()
                FROM candidate
                WHERE t.id = candidate.id
                RETURNING t.id, t.kb_id, t.document_id, t.index_version, t.status,
                          t.lease_version, t.cancel_requested,
                          t.retry_count, t.max_retries, t.next_retry_at, t.heartbeat_at,
                          t.worker_id, t.last_error, t.started_at, t.completed_at,
                          t.file_path, t.filename, t.filetype, t.file_size,
                          t.content_hash, t.source_key, t.index_fingerprint,
                          t.is_new_document, t.old_file_path,
                          t.chunk_count, t.reused_chunk_count, t.embedded_chunk_count,
                          t.created_at, t.updated_at
                """, ROW_MAPPER, workerId);
        return tasks.stream().findFirst();
    }

    public boolean touchHeartbeat(String taskId, String workerId, long leaseVersion) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_version = ?
                  AND cancel_requested = FALSE
                """, taskId, workerId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean touchHeartbeat(String taskId, long leaseVersion, String workerId) {
        return touchHeartbeat(taskId, workerId, leaseVersion);
    }

    @Deprecated
    public boolean touchHeartbeat(String taskId, String workerId) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET heartbeat_at = NOW(), updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING' AND (worker_id = ? OR worker_id IS NULL)
                """, taskId, workerId) == 1;
    }

    public boolean markSucceeded(String taskId, String workerId, long leaseVersion, int chunkCount, int reusedCount, int embeddedCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'SUCCEEDED',
                    completed_at = NOW(),
                    heartbeat_at = NOW(),
                    chunk_count = ?,
                    reused_chunk_count = ?,
                    embedded_chunk_count = ?,
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_version = ?
                  AND cancel_requested = FALSE
                """, chunkCount, reusedCount, embeddedCount, taskId, workerId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markSucceeded(String taskId, long leaseVersion, int chunkCount, int reusedCount, int embeddedCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'SUCCEEDED',
                    completed_at = NOW(),
                    heartbeat_at = NOW(),
                    chunk_count = ?,
                    reused_chunk_count = ?,
                    embedded_chunk_count = ?,
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND lease_version = ?
                  AND cancel_requested = FALSE
                """, chunkCount, reusedCount, embeddedCount, taskId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markSucceeded(String taskId, int chunkCount, int reusedCount, int embeddedCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'SUCCEEDED',
                    completed_at = NOW(),
                    heartbeat_at = NOW(),
                    chunk_count = ?,
                    reused_chunk_count = ?,
                    embedded_chunk_count = ?,
                    last_error = NULL,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status = 'RUNNING'
                """, chunkCount, reusedCount, embeddedCount, taskId) == 1;
    }

    public boolean markRetryWait(String taskId, String workerId, long leaseVersion, String errorMessage, LocalDateTime nextRetryAt, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'RETRY_WAIT',
                    retry_count = ?,
                    next_retry_at = ?,
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_version = ?
                """, retryCount, nextRetryAt, sanitizeError(errorMessage), taskId, workerId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markRetryWait(String taskId, long leaseVersion, String errorMessage, LocalDateTime nextRetryAt, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'RETRY_WAIT',
                    retry_count = ?,
                    next_retry_at = ?,
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND lease_version = ?
                """, retryCount, nextRetryAt, sanitizeError(errorMessage), taskId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markRetryWait(String taskId, String errorMessage, LocalDateTime nextRetryAt, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'RETRY_WAIT',
                    retry_count = ?,
                    next_retry_at = ?,
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('PENDING', 'RUNNING')
                """, retryCount, nextRetryAt, sanitizeError(errorMessage), taskId) == 1;
    }

    public boolean markFailed(String taskId, String workerId, long leaseVersion, String errorMessage, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'FAILED',
                    retry_count = ?,
                    completed_at = NOW(),
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_version = ?
                """, retryCount, sanitizeError(errorMessage), taskId, workerId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markFailed(String taskId, long leaseVersion, String errorMessage, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'FAILED',
                    retry_count = ?,
                    completed_at = NOW(),
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND lease_version = ?
                """, retryCount, sanitizeError(errorMessage), taskId, leaseVersion) == 1;
    }

    @Deprecated
    public boolean markFailed(String taskId, String errorMessage, int retryCount) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'FAILED',
                    retry_count = ?,
                    completed_at = NOW(),
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid) AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                """, retryCount, sanitizeError(errorMessage), taskId) == 1;
    }

    public boolean markCancelled(String taskId, String workerId, long leaseVersion, String message) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'CANCELLED',
                    completed_at = NOW(),
                    worker_id = NULL,
                    last_error = ?,
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND worker_id = ?
                  AND lease_version = ?
                """, sanitizeError(message), taskId, workerId, leaseVersion) == 1;
    }

    public boolean markCancelled(String taskId, long leaseVersion) {
        return jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'CANCELLED',
                    completed_at = NOW(),
                    worker_id = NULL,
                    last_error = '任务已被取消',
                    updated_at = NOW()
                WHERE id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                  AND lease_version = ?
                """, taskId, leaseVersion) == 1;
    }

    public DocumentIndexTask findAndLockForCommit(String taskId) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = CAST(? AS uuid) FOR UPDATE",
                ROW_MAPPER, taskId);
        return tasks.stream().findFirst().orElse(null);
    }

    public int requestCancellationByDocumentId(String documentId) {
        int cancelledPending = jdbcTemplate.update("""
                UPDATE document_index_task
                SET status = 'CANCELLED',
                    cancel_requested = TRUE,
                    completed_at = NOW(),
                    last_error = '任务已被取消',
                    updated_at = NOW()
                WHERE document_id = CAST(? AS uuid)
                  AND status IN ('PENDING', 'RETRY_WAIT')
                """, documentId);

        int markedRunning = jdbcTemplate.update("""
                UPDATE document_index_task
                SET cancel_requested = TRUE,
                    updated_at = NOW()
                WHERE document_id = CAST(? AS uuid)
                  AND status = 'RUNNING'
                """, documentId);

        return cancelledPending + markedRunning;
    }

    public boolean isFilePathInUse(String filePath) {
        return isFilePathInUse(filePath, null);
    }

    public boolean isFilePathInUse(String filePath, String excludeTaskId) {
        if (filePath == null) {
            return false;
        }
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM document WHERE metadata->>'filePath' = ?
                    UNION ALL
                    SELECT 1 FROM document_index_task
                    WHERE file_path = ?
                      AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                """ + (excludeTaskId != null ? " AND id != CAST(? AS uuid)" : "") + ")";
        Boolean inUse;
        if (excludeTaskId != null) {
            inUse = jdbcTemplate.queryForObject(sql, Boolean.class, filePath, filePath, excludeTaskId);
        } else {
            inUse = jdbcTemplate.queryForObject(sql, Boolean.class, filePath, filePath);
        }
        return Boolean.TRUE.equals(inUse);
    }

    @Transactional
    public List<DocumentIndexTask> recoverStaleRunningTasks(
            Duration timeout,
            IndexRetryPolicy retryPolicy,
            int limit
    ) {
        List<DocumentIndexTask> staleTasks = jdbcTemplate.query("""
                SELECT id, kb_id, document_id, index_version, status,
                       lease_version, cancel_requested,
                       retry_count, max_retries, next_retry_at, heartbeat_at,
                       worker_id, last_error, started_at, completed_at,
                       file_path, filename, filetype, file_size,
                       content_hash, source_key, index_fingerprint,
                       is_new_document, old_file_path,
                       chunk_count, reused_chunk_count, embedded_chunk_count,
                       created_at, updated_at
                FROM document_index_task
                WHERE status = 'RUNNING'
                  AND COALESCE(heartbeat_at, started_at, updated_at) < NOW() - (? * INTERVAL '1 second')
                ORDER BY COALESCE(heartbeat_at, started_at, updated_at) ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, ROW_MAPPER, timeout.toSeconds(), limit);

        if (staleTasks.isEmpty()) {
            return List.of();
        }

        List<DocumentIndexTask> updatedList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (DocumentIndexTask task : staleTasks) {
            int currentRetry = task.getRetryCount() != null ? task.getRetryCount() : 0;
            int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : retryPolicy.getMaxRetries();
            boolean isCancel = Boolean.TRUE.equals(task.getCancelRequested());

            if (isCancel) {
                jdbcTemplate.update("""
                        UPDATE document_index_task
                        SET status = 'CANCELLED',
                            completed_at = ?,
                            worker_id = NULL,
                            last_error = '任务已被取消',
                            updated_at = ?
                        WHERE id = CAST(? AS uuid)
                        """, now, now, task.getId());
                task.setStatus(DocumentIndexTaskStatus.CANCELLED);
                task.setCompletedAt(now);
                task.setWorkerId(null);
                task.setLastError("任务已被取消");
                task.setUpdatedAt(now);
                updatedList.add(task);
            } else if (currentRetry < maxRetries) {
                Duration backoff = retryPolicy.calculateBackoff(currentRetry);
                LocalDateTime nextRetryAt = now.plus(backoff);
                int nextRetry = currentRetry + 1;

                jdbcTemplate.update("""
                        UPDATE document_index_task
                        SET status = 'RETRY_WAIT',
                            retry_count = ?,
                            next_retry_at = ?,
                            worker_id = NULL,
                            last_error = '任务心跳超时，已自动恢复为重试等待',
                            updated_at = ?
                        WHERE id = CAST(? AS uuid)
                        """, nextRetry, nextRetryAt, now, task.getId());

                task.setStatus(DocumentIndexTaskStatus.RETRY_WAIT);
                task.setRetryCount(nextRetry);
                task.setNextRetryAt(nextRetryAt);
                task.setWorkerId(null);
                task.setLastError("任务心跳超时，已自动恢复为重试等待");
                task.setUpdatedAt(now);
                updatedList.add(task);
            } else {
                int nextRetry = currentRetry + 1;
                jdbcTemplate.update("""
                        UPDATE document_index_task
                        SET status = 'FAILED',
                            retry_count = ?,
                            completed_at = ?,
                            worker_id = NULL,
                            last_error = '任务心跳超时，重试次数已耗尽',
                            updated_at = ?
                        WHERE id = CAST(? AS uuid)
                        """, nextRetry, now, now, task.getId());

                task.setStatus(DocumentIndexTaskStatus.FAILED);
                task.setRetryCount(nextRetry);
                task.setCompletedAt(now);
                task.setWorkerId(null);
                task.setLastError("任务心跳超时，重试次数已耗尽");
                task.setUpdatedAt(now);
                updatedList.add(task);
            }
        }

        return updatedList;
    }

    public List<DocumentIndexTask> recoverStaleRunningTasks(Duration timeout, Duration backoff, int limit) {
        return jdbcTemplate.query("""
                WITH stale AS (
                    SELECT id, retry_count, max_retries, cancel_requested
                    FROM document_index_task
                    WHERE status = 'RUNNING'
                      AND COALESCE(heartbeat_at, started_at, updated_at) < NOW() - (? * INTERVAL '1 second')
                    ORDER BY COALESCE(heartbeat_at, started_at, updated_at) ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                ), updated AS (
                    UPDATE document_index_task t
                    SET status = CASE
                            WHEN stale.cancel_requested = TRUE THEN 'CANCELLED'
                            WHEN stale.retry_count < stale.max_retries THEN 'RETRY_WAIT'
                            ELSE 'FAILED'
                        END,
                        retry_count = CASE
                            WHEN stale.cancel_requested = TRUE THEN stale.retry_count
                            ELSE stale.retry_count + 1
                        END,
                        next_retry_at = CASE
                            WHEN stale.cancel_requested = TRUE THEN NULL
                            WHEN stale.retry_count < stale.max_retries THEN NOW() + (LEAST(? * POWER(2.0, stale.retry_count), 60.0) * INTERVAL '1 second')
                            ELSE NULL
                        END,
                        completed_at = CASE
                            WHEN stale.cancel_requested = TRUE OR stale.retry_count >= stale.max_retries THEN NOW()
                            ELSE NULL
                        END,
                        worker_id = NULL,
                        last_error = CASE
                            WHEN stale.cancel_requested = TRUE THEN '任务已被取消'
                            WHEN stale.retry_count < stale.max_retries THEN '任务心跳超时，已自动恢复为重试等待'
                            ELSE '任务心跳超时，重试次数已耗尽'
                        END,
                        updated_at = NOW()
                    FROM stale
                    WHERE t.id = stale.id
                    RETURNING t.id, t.kb_id, t.document_id, t.index_version, t.status,
                              t.retry_count, t.max_retries, t.next_retry_at, t.heartbeat_at,
                              t.worker_id, t.last_error, t.started_at, t.completed_at,
                              t.file_path, t.filename, t.filetype, t.file_size,
                              t.content_hash, t.source_key, t.index_fingerprint,
                              t.is_new_document, t.old_file_path,
                              t.chunk_count, t.reused_chunk_count, t.embedded_chunk_count,
                              t.lease_version, t.cancel_requested,
                              t.created_at, t.updated_at
                )
                SELECT id, kb_id, document_id, index_version, status,
                       retry_count, max_retries, next_retry_at, heartbeat_at,
                       worker_id, last_error, started_at, completed_at,
                       file_path, filename, filetype, file_size,
                       content_hash, source_key, index_fingerprint,
                       is_new_document, old_file_path,
                       chunk_count, reused_chunk_count, embedded_chunk_count,
                       lease_version, cancel_requested,
                       created_at, updated_at
                FROM updated
                """, ROW_MAPPER, timeout.toSeconds(), limit, backoff.toSeconds());
    }

    public Optional<DocumentIndexTask> findById(String taskId) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE id = CAST(? AS uuid)", ROW_MAPPER, taskId);
        return tasks.stream().findFirst();
    }

    public Optional<DocumentIndexTask> findLatestByDocumentId(String documentId) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE document_id = CAST(? AS uuid) ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER, documentId);
        return tasks.stream().findFirst();
    }

    public Optional<DocumentIndexTask> findLatestByKbIdAndSourceKey(String kbId, String sourceKey) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE kb_id = CAST(? AS uuid) AND source_key = ? ORDER BY index_version DESC, created_at DESC LIMIT 1",
                ROW_MAPPER, kbId, sourceKey);
        return tasks.stream().findFirst();
    }

    public Optional<DocumentIndexTask> findLatestActiveByKbIdAndSourceKey(String kbId, String sourceKey) {
        List<DocumentIndexTask> tasks = jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE kb_id = CAST(? AS uuid) AND source_key = ? AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT') ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER, kbId, sourceKey);
        return tasks.stream().findFirst();
    }

    public int deleteByDocumentId(String documentId) {
        return jdbcTemplate.update("DELETE FROM document_index_task WHERE document_id = CAST(? AS uuid)", documentId);
    }

    static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return null;
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

    private static Long getLongOrNull(ResultSet rs, String column) {
        try {
            long val = rs.getLong(column);
            return rs.wasNull() ? null : val;
        } catch (SQLException e) {
            return null;
        }
    }

    private static Boolean getBooleanOrNull(ResultSet rs, String column) {
        try {
            boolean val = rs.getBoolean(column);
            return rs.wasNull() ? null : val;
        } catch (SQLException e) {
            return null;
        }
    }

    private static final RowMapper<DocumentIndexTask> ROW_MAPPER = (rs, rowNum) -> DocumentIndexTask.builder()
            .id(rs.getString("id"))
            .kbId(rs.getString("kb_id"))
            .documentId(rs.getString("document_id"))
            .indexVersion(rs.getInt("index_version"))
            .status(DocumentIndexTaskStatus.valueOf(rs.getString("status")))
            .leaseVersion(getLongOrNull(rs, "lease_version"))
            .cancelRequested(getBooleanOrNull(rs, "cancel_requested"))
            .retryCount(rs.getInt("retry_count"))
            .maxRetries(rs.getInt("max_retries"))
            .nextRetryAt(timestamp(rs, "next_retry_at"))
            .heartbeatAt(timestamp(rs, "heartbeat_at"))
            .workerId(rs.getString("worker_id"))
            .lastError(rs.getString("last_error"))
            .startedAt(timestamp(rs, "started_at"))
            .completedAt(timestamp(rs, "completed_at"))
            .filePath(rs.getString("file_path"))
            .filename(rs.getString("filename"))
            .filetype(rs.getString("filetype"))
            .fileSize(rs.getLong("file_size"))
            .contentHash(rs.getString("content_hash"))
            .sourceKey(rs.getString("source_key"))
            .indexFingerprint(rs.getString("index_fingerprint"))
            .isNewDocument(rs.getBoolean("is_new_document"))
            .oldFilePath(rs.getString("old_file_path"))
            .chunkCount(rs.getInt("chunk_count"))
            .reusedChunkCount(rs.getInt("reused_chunk_count"))
            .embeddedChunkCount(rs.getInt("embedded_chunk_count"))
            .createdAt(timestamp(rs, "created_at"))
            .updatedAt(timestamp(rs, "updated_at"))
            .build();
}
