package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskConcurrencyAndLifecycleTest {

    @Test
    void concurrentClaimOnlyOnce() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        DocumentIndexTask candidateTask = DocumentIndexTask.builder()
                .id("task-claim-once")
                .status(DocumentIndexTaskStatus.RUNNING)
                .build();

        // Simulate atomic row lock: first claimant gets the task, second gets empty
        AtomicBoolean claimed = new AtomicBoolean(false);
        when(jdbcTemplate.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class), anyString()))
                .thenAnswer(invocation -> {
                    String workerId = invocation.getArgument(2);
                    if (claimed.compareAndSet(false, true)) {
                        return List.of(DocumentIndexTask.builder()
                                .id("task-claim-once")
                                .status(DocumentIndexTaskStatus.RUNNING)
                                .workerId(workerId)
                                .build());
                    }
                    return List.of();
                });

        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String workerId = "worker-" + i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    Optional<DocumentIndexTask> task = store.claimNextTask(workerId);
                    if (task.isPresent()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    void retryTaskCannotRunBeforeNextRetryAt() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        // When candidate query runs, tasks in RETRY_WAIT whose next_retry_at is in the future
        // are excluded by the SQL WHERE clause: (next_retry_at IS NULL OR next_retry_at <= NOW())
        when(jdbcTemplate.query(contains("next_retry_at IS NULL OR next_retry_at <= NOW()"),
                any(RowMapper.class), eq("worker-1")))
                .thenReturn(List.of());

        Optional<DocumentIndexTask> claimed = store.claimNextTask("worker-1");

        assertThat(claimed).isEmpty();
        verify(jdbcTemplate).query(
                contains("next_retry_at IS NULL OR next_retry_at <= NOW()"),
                any(RowMapper.class), eq("worker-1"));
    }

    @Test
    void staleRunningRecoveryTransitionsStaleTasks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        DocumentIndexTask recoveredToRetry = DocumentIndexTask.builder()
                .id("stale-retry")
                .status(DocumentIndexTaskStatus.RETRY_WAIT)
                .retryCount(1)
                .maxRetries(3)
                .lastError("任务心跳超时，已自动恢复为重试等待")
                .build();

        DocumentIndexTask recoveredToFailed = DocumentIndexTask.builder()
                .id("stale-failed")
                .status(DocumentIndexTaskStatus.FAILED)
                .retryCount(3)
                .maxRetries(3)
                .lastError("任务心跳超时，重试次数已耗尽")
                .build();

        when(jdbcTemplate.query(contains("WITH stale AS"), any(RowMapper.class),
                eq(120L), eq(10), eq(2L)))
                .thenReturn(List.of(recoveredToRetry, recoveredToFailed));

        List<DocumentIndexTask> recovered = store.recoverStaleRunningTasks(
                Duration.ofSeconds(120), Duration.ofSeconds(2), 10);

        assertThat(recovered).hasSize(2);
        assertThat(recovered.get(0).getStatus()).isEqualTo(DocumentIndexTaskStatus.RETRY_WAIT);
        assertThat(recovered.get(0).getLastError()).contains("已自动恢复为重试等待");
        assertThat(recovered.get(1).getStatus()).isEqualTo(DocumentIndexTaskStatus.FAILED);
        assertThat(recovered.get(1).getLastError()).contains("重试次数已耗尽");
    }

    @Test
    void retryExhaustionTransitionsToFailed() throws Exception {
        DocumentIndexTaskStore store = mock(DocumentIndexTaskStore.class);
        DocumentIndexTaskExecutor executor = mock(DocumentIndexTaskExecutor.class);
        IndexRetryPolicy retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                store, executor, retryPolicy, "worker-exhaust", 1);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-exhaust")
                .retryCount(3) // retry limit reached
                .maxRetries(3)
                .status(DocumentIndexTaskStatus.RUNNING)
                .build();

        when(store.claimNextTask("worker-exhaust")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new IOException("Permanent network failure"));

        worker.processNextTask();

        verify(store).markFailed("task-exhaust", "Permanent network failure", 4);
        verify(store, never()).markRetryWait(anyString(), anyString(), any(), anyInt());
        worker.destroy();
    }

    @Test
    void duplicateDocumentVersionTaskRejection() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-dup")
                .documentId("doc-dup")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.PENDING)
                .build();

        when(jdbcTemplate.update(contains("INSERT INTO document_index_task"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint uk_document_index_task_doc_version"));

        assertThatThrownBy(() -> store.createTask(task))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复创建文档索引任务");
    }

    @Test
    void unchangedChunkReusesEmbedding() {
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        RagService ragService = mock(RagService.class);
        DocumentIndexStore indexStore = mock(DocumentIndexStore.class);
        DocumentIndexFingerprint fingerprint = new DocumentIndexFingerprint(
                "bge-m3", 1024, "none", "v1");

        String docId = "doc-reuse-test";
        String unchangedContent = "stable chunk content";
        float[] existingVector = new float[]{0.1f, 0.2f};

        when(chunkMapper.selectByDocumentId(docId)).thenReturn(List.of(
                ChunkBgeM3.builder()
                        .id("chunk-old-1")
                        .content(unchangedContent)
                        .chunkHash(DocumentHashing.sha256(unchangedContent))
                        .indexFingerprint(fingerprint.current())
                        .embedding(existingVector)
                        .createdAt(LocalDateTime.now().minusHours(1))
                        .build()
        ));

        when(ragService.embed("brand new chunk")).thenReturn(new float[]{0.3f, 0.4f});

        IncrementalDocumentIndexService indexService = new IncrementalDocumentIndexService(
                chunkMapper, ragService, indexStore, fingerprint);

        Document document = Document.builder()
                .id(docId)
                .kbId("kb-1")
                .indexVersion(2)
                .build();

        IncrementalDocumentIndexService.IndexResult result = indexService.replaceIndex(
                document, false,
                List.of(
                        new IncrementalDocumentIndexService.ChunkInput(unchangedContent, "{}"),
                        new IncrementalDocumentIndexService.ChunkInput("brand new chunk", "{}")
                )
        );

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.reusedChunkCount()).isEqualTo(1);
        assertThat(result.embeddedChunkCount()).isEqualTo(1);

        verify(ragService, never()).embed(unchangedContent);
        verify(ragService).embed("brand new chunk");
    }

    @Test
    void workerFailureDoesNotLeavePermanentRunningTask() throws Exception {
        DocumentIndexTaskStore store = mock(DocumentIndexTaskStore.class);
        DocumentIndexTaskExecutor executor = mock(DocumentIndexTaskExecutor.class);
        IndexRetryPolicy retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                store, executor, retryPolicy, "worker-crash", 1);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-crash")
                .status(DocumentIndexTaskStatus.RUNNING)
                .retryCount(0)
                .maxRetries(3)
                .build();

        when(store.claimNextTask("worker-crash")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new RuntimeException("Crash simulation"));

        worker.processNextTask();

        // 1. Worker marks it retryable (RETRY_WAIT) rather than leaving in RUNNING
        verify(store).markRetryWait(eq("task-crash"), eq("Crash simulation"), any(), eq(1));
        // 2. Active tasks snapshot is cleared
        assertThat(worker.getActiveTasksSnapshot()).isEmpty();

        worker.destroy();
    }

    @Test
    void taskRetryReexecutionAfterCrashIsIdempotentAndDoesNotThrowConflict() throws Exception {
        DocumentIndexTaskStore store = mock(DocumentIndexTaskStore.class);
        DocumentStorageService storage = mock(DocumentStorageService.class);
        MarkdownParserService markdown = mock(MarkdownParserService.class);
        DocumentParserService parser = mock(DocumentParserService.class);
        IncrementalDocumentIndexService incremental = mock(IncrementalDocumentIndexService.class);
        com.kama.jmindops.mapper.DocumentMapper mapper = mock(com.kama.jmindops.mapper.DocumentMapper.class);

        DocumentIndexTaskExecutor executor = new DocumentIndexTaskExecutor(
                storage, markdown, parser, incremental, mapper);
        IndexRetryPolicy retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                store, executor, retryPolicy, "worker-retry", 1);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-recovered")
                .kbId("kb-1")
                .documentId("doc-1")
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.RUNNING)
                .retryCount(1)
                .maxRetries(3)
                .contentHash("hash-v2")
                .indexFingerprint("fp-v2")
                .filePath("docs/v2.md")
                .filename("v2.md")
                .filetype("md")
                .fileSize(100L)
                .sourceKey("v2.md")
                .build();

        // Simulate document in DB already updated by previous aborted worker run before crash
        Document existingInDb = Document.builder()
                .id("doc-1")
                .kbId("kb-1")
                .indexVersion(2)
                .indexStatus("READY")
                .contentHash("hash-v2")
                .indexFingerprint("fp-v2")
                .chunkCount(5)
                .build();
        when(mapper.selectById("doc-1")).thenReturn(existingInDb);
        when(store.claimNextTask("worker-retry")).thenReturn(Optional.of(task));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        // Succeeded cleanly without throwing duplicate key or optimistic lock exception
        verify(store).markSucceeded("task-recovered", 5, 5, 0);
        verify(incremental, never()).replaceIndex(any(), anyBoolean(), any());

        worker.destroy();
    }
}
