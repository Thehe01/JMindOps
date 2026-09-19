package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskStoreTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentIndexTaskStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new DocumentIndexTaskStore(jdbcTemplate);
    }

    @Test
    void sanitizesSensitiveAndMultilineFailureDetails() {
        String sanitized = DocumentIndexTaskStore.sanitizeError(
                "provider failed\napi_key=sk-secret token:bearer-value password=123");

        assertThat(sanitized).isEqualTo("provider failed api_key=****** token=****** password=******");
    }

    @Test
    void boundsPersistedFailureLength() {
        assertThat(DocumentIndexTaskStore.sanitizeError("x".repeat(2_000))).hasSize(1_000);
    }

    @Test
    void createTaskExecutesInsertWithParameters() {
        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .kbId("22222222-2222-2222-2222-222222222222")
                .documentId("33333333-3333-3333-3333-333333333333")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .filePath("kb/doc.txt")
                .filename("doc.txt")
                .filetype("txt")
                .fileSize(100L)
                .contentHash("hash123")
                .sourceKey("doc.txt")
                .indexFingerprint("fp123")
                .isNewDocument(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        store.createTask(task);

        verify(jdbcTemplate).update(contains("INSERT INTO document_index_task"),
                eq(task.getId()),
                eq(task.getKbId()),
                eq(task.getDocumentId()),
                eq(task.getIndexVersion()),
                eq("PENDING"),
                eq(0),
                eq(3),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq(null),
                eq("kb/doc.txt"),
                eq("doc.txt"),
                eq("txt"),
                eq(100L),
                eq("hash123"),
                eq("doc.txt"),
                eq("fp123"),
                eq(true),
                eq(null),
                eq(0),
                eq(0),
                eq(0),
                eq(task.getCreatedAt()),
                eq(task.getUpdatedAt())
        );
    }

    @Test
    void createTaskTranslatesDuplicateKeyExceptionToBizException() {
        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .kbId("22222222-2222-2222-2222-222222222222")
                .documentId("33333333-3333-3333-3333-333333333333")
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.PENDING)
                .filePath("path")
                .filename("f")
                .filetype("txt")
                .fileSize(10L)
                .contentHash("h")
                .sourceKey("k")
                .indexFingerprint("fp")
                .build();

        when(jdbcTemplate.update(contains("INSERT INTO document_index_task"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> store.createTask(task))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复创建文档索引任务");
    }

    @Test
    void claimNextTaskUsesForUpdateSkipLocked() {
        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId("worker-1")
                .build();

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("worker-1")))
                .thenReturn(List.of(task));

        Optional<DocumentIndexTask> claimed = store.claimNextTask("worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getId()).isEqualTo("11111111-1111-1111-1111-111111111111");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("worker-1"));
        assertThat(sqlCaptor.getValue())
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("WITH candidate AS")
                .contains("next_retry_at IS NULL OR next_retry_at <= NOW()");
    }

    @Test
    void touchHeartbeatUpdatesTimestampForRunningTask() {
        when(jdbcTemplate.update(anyString(), eq("task-1"), eq("worker-1"))).thenReturn(1);

        boolean updated = store.touchHeartbeat("task-1", "worker-1");

        assertThat(updated).isTrue();
        verify(jdbcTemplate).update(contains("status = 'RUNNING'"), eq("task-1"), eq("worker-1"));
    }

    @Test
    void markSucceededUpdatesStatusAndChunkCounters() {
        when(jdbcTemplate.update(anyString(), eq(10), eq(6), eq(4), eq("task-1"))).thenReturn(1);

        boolean marked = store.markSucceeded("task-1", 10, 6, 4);

        assertThat(marked).isTrue();
        verify(jdbcTemplate).update(contains("status = 'SUCCEEDED'"), eq(10), eq(6), eq(4), eq("task-1"));
    }

    @Test
    void markRetryWaitUpdatesStatusAndIncrementsRetryCount() {
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(4);
        when(jdbcTemplate.update(anyString(), eq(1), eq(nextRetryAt), eq("network error"), eq("task-1"))).thenReturn(1);

        boolean marked = store.markRetryWait("task-1", "network error", nextRetryAt, 1);

        assertThat(marked).isTrue();
        verify(jdbcTemplate).update(contains("status = 'RETRY_WAIT'"), eq(1), eq(nextRetryAt), eq("network error"), eq("task-1"));
    }

    @Test
    void markFailedUpdatesStatusAndLastError() {
        when(jdbcTemplate.update(anyString(), eq(3), eq("retries exhausted"), eq("task-1"))).thenReturn(1);

        boolean marked = store.markFailed("task-1", "retries exhausted", 3);

        assertThat(marked).isTrue();
        verify(jdbcTemplate).update(contains("status = 'FAILED'"), eq(3), eq("retries exhausted"), eq("task-1"));
    }

    @Test
    void recoverStaleRunningTasksUsesTimeoutAndBatchSize() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(120L), eq(10), eq(2L)))
                .thenReturn(List.of());

        List<DocumentIndexTask> recovered = store.recoverStaleRunningTasks(
                Duration.ofSeconds(120), Duration.ofSeconds(2), 10);

        assertThat(recovered).isEmpty();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(120L), eq(10), eq(2L));
        assertThat(sqlCaptor.getValue())
                .contains("status = 'RUNNING'")
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("CASE")
                .contains("stale.retry_count < stale.max_retries THEN 'RETRY_WAIT'");
    }

    @Test
    void lockDocumentAcquiresAdvisoryLock() {
        when(jdbcTemplate.queryForObject(contains("pg_advisory_xact_lock"), any(RowMapper.class), eq("kb-1:guide.md")))
                .thenReturn(Boolean.TRUE);

        store.lockDocument("kb-1", "guide.md");

        verify(jdbcTemplate).queryForObject(contains("pg_advisory_xact_lock"), any(RowMapper.class), eq("kb-1:guide.md"));
    }

    @Test
    void findLatestActiveByKbIdAndSourceKeyQueriesActiveStatuses() {
        DocumentIndexTask task = DocumentIndexTask.builder().id("task-active").build();
        when(jdbcTemplate.query(contains("WHERE kb_id = CAST(? AS uuid) AND source_key = ?"),
                any(RowMapper.class), eq("kb-1"), eq("guide.md")))
                .thenReturn(List.of(task));

        Optional<DocumentIndexTask> result = store.findLatestActiveByKbIdAndSourceKey("kb-1", "guide.md");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("task-active");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("kb-1"), eq("guide.md"));
        assertThat(sqlCaptor.getValue()).contains("status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')");
    }
}
