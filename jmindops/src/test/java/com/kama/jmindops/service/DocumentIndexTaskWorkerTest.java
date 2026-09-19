package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskWorkerTest {

    private DocumentIndexTaskStore store;
    private DocumentIndexTaskExecutor executor;
    private IndexRetryPolicy retryPolicy;
    private DocumentIndexTaskWorker worker;

    @BeforeEach
    void setUp() {
        store = mock(DocumentIndexTaskStore.class);
        executor = mock(DocumentIndexTaskExecutor.class);
        retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        worker = new DocumentIndexTaskWorker(store, executor, retryPolicy, "test-worker-1", 1);
    }

    @AfterEach
    void tearDown() {
        worker.destroy();
    }

    @Test
    void processesTaskSuccessfullyAndMarksSucceeded() throws Exception {
        DocumentIndexTask task = testTask(0);
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenReturn(new DocumentIndexTaskExecutor.ExecutionResult(5, 3, 2));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        verify(store).markSucceeded("task-1", 5, 3, 2);
        verify(store, never()).markRetryWait(anyString(), anyString(), any(), anyInt());
        verify(store, never()).markFailed(anyString(), anyString(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).doesNotContainKey("task-1");
    }

    @Test
    void retryableErrorTransitionsToRetryWait() throws Exception {
        DocumentIndexTask task = testTask(0);
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new IOException("Connection reset by peer"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        verify(store).markRetryWait(eq("task-1"), eq("Connection reset by peer"), any(LocalDateTime.class), eq(1));
        verify(store, never()).markSucceeded(anyString(), anyInt(), anyInt(), anyInt());
        verify(store, never()).markFailed(anyString(), anyString(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).doesNotContainKey("task-1");
    }

    @Test
    void nonRetryableErrorTransitionsToFailed() throws Exception {
        DocumentIndexTask task = testTask(0);
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new BizException("文档中没有可索引的文本内容"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        verify(store).markFailed(eq("task-1"), eq("文档中没有可索引的文本内容"), eq(1));
        verify(store, never()).markRetryWait(anyString(), anyString(), any(), anyInt());
        verify(store, never()).markSucceeded(anyString(), anyInt(), anyInt(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).doesNotContainKey("task-1");
    }

    @Test
    void retryExhaustionTransitionsToFailed() throws Exception {
        DocumentIndexTask task = testTask(3); // already retried 3 times
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new IOException("Still failing after 3 retries"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        verify(store).markFailed(eq("task-1"), eq("Still failing after 3 retries"), eq(4));
        verify(store, never()).markRetryWait(anyString(), anyString(), any(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).doesNotContainKey("task-1");
    }

    @Test
    void workerFailureDoesNotLeavePermanentRunningTask() throws Exception {
        DocumentIndexTask task = testTask(0);
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new RuntimeException("Worker unexpected runtime explosion"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        // Since RuntimeException is retryable, it transitioned to RETRY_WAIT
        verify(store).markRetryWait(eq("task-1"), eq("Worker unexpected runtime explosion"), any(), eq(1));
        // And task is cleared from active tasks, ensuring it's not held locally
        assertThat(worker.getActiveTasksSnapshot()).doesNotContainKey("task-1");
    }

    @Test
    void heartbeatsActiveTasksTouchesStoreHeartbeat() throws Exception {
        DocumentIndexTask task = testTask(0);
        when(store.claimNextTask("test-worker-1")).thenReturn(Optional.of(task));
        // Simulate execution that pauses while we check heartbeats
        when(executor.execute(task)).thenAnswer(invocation -> {
            worker.heartbeatActiveTasks();
            return new DocumentIndexTaskExecutor.ExecutionResult(1, 0, 1);
        });

        worker.processNextTask();

        verify(store).touchHeartbeat("task-1", "test-worker-1");
    }

    private DocumentIndexTask testTask(int retryCount) {
        return DocumentIndexTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .documentId("doc-1")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.RUNNING)
                .retryCount(retryCount)
                .maxRetries(3)
                .filePath("docs/sample.md")
                .filename("sample.md")
                .filetype("md")
                .fileSize(100L)
                .contentHash("hash")
                .sourceKey("sample.md")
                .indexFingerprint("fp")
                .isNewDocument(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
