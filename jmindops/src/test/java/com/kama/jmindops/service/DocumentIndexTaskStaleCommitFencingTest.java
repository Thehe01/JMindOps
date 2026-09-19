package com.kama.jmindops.service;

import com.kama.jmindops.exception.StaleDocumentIndexLeaseException;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskStaleCommitFencingTest {

    private DocumentIndexTaskStore store;
    private DocumentIndexStore indexStore;
    private DocumentIndexCommitService commitService;

    @BeforeEach
    void setUp() {
        store = mock(DocumentIndexTaskStore.class);
        indexStore = mock(DocumentIndexStore.class);
        commitService = new DocumentIndexCommitService(store, indexStore);
    }

    @Test
    void commitPreparedIndexThrowsStaleLeaseExceptionWhenTaskLeaseVersionAdvanced() {
        String taskId = "task-stale-commit";
        String workerId = "worker-1";
        long workerLease = 1L;
        long dbLease = 2L; // Reclaimed by another worker

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(workerLease)
                .build();

        DocumentIndexTask lockedTaskInDb = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId("worker-2")
                .leaseVersion(dbLease)
                .cancelRequested(false)
                .build();

        when(store.findAndLockForCommit(taskId)).thenReturn(lockedTaskInDb);

        Document document = Document.builder().id("doc-1").kbId("kb-1").indexVersion(1).build();
        IncrementalDocumentIndexService.IndexResult result = new IncrementalDocumentIndexService.IndexResult(5, 3, 2);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                document, false, List.of(), result);

        assertThatThrownBy(() -> commitService.commit(task, workerId, workerLease, prepared))
                .isInstanceOf(StaleDocumentIndexLeaseException.class)
                .hasMessageContaining("Stale lease on commit");

        verify(indexStore, never()).replace(any(), anyBoolean(), any());
        verify(store, never()).markSucceeded(anyString(), anyString(), anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void commitPreparedIndexThrowsStaleLeaseExceptionWhenTaskNotInRunningStatus() {
        String taskId = "task-not-running";
        String workerId = "worker-1";
        long workerLease = 1L;

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(workerLease)
                .build();

        DocumentIndexTask lockedTaskInDb = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RETRY_WAIT) // transitioned away
                .workerId(workerId)
                .leaseVersion(workerLease)
                .cancelRequested(false)
                .build();

        when(store.findAndLockForCommit(taskId)).thenReturn(lockedTaskInDb);

        Document document = Document.builder().id("doc-1").kbId("kb-1").indexVersion(1).build();
        IncrementalDocumentIndexService.IndexResult result = new IncrementalDocumentIndexService.IndexResult(5, 3, 2);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                document, false, List.of(), result);

        assertThatThrownBy(() -> commitService.commit(task, workerId, workerLease, prepared))
                .isInstanceOf(StaleDocumentIndexLeaseException.class);

        verify(indexStore, never()).replace(any(), anyBoolean(), any());
    }

    @Test
    void commitPreparedIndexSucceedsWhenLeaseMatchesAndTaskRunning() {
        String taskId = "task-ok-commit";
        String workerId = "worker-1";
        long lease = 2L;

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(lease)
                .build();

        DocumentIndexTask lockedTaskInDb = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(lease)
                .cancelRequested(false)
                .build();

        when(store.findAndLockForCommit(taskId)).thenReturn(lockedTaskInDb);
        when(store.markSucceeded(taskId, workerId, lease, 5, 3, 2)).thenReturn(true);

        Document document = Document.builder().id("doc-1").kbId("kb-1").indexVersion(1).build();
        IncrementalDocumentIndexService.IndexResult result = new IncrementalDocumentIndexService.IndexResult(5, 3, 2);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                document, false, List.of(), result);

        IncrementalDocumentIndexService.IndexResult commitResult = commitService.commit(task, workerId, lease, prepared);

        assertThat(commitResult).isNotNull();
        assertThat(commitResult.chunkCount()).isEqualTo(5);
        assertThat(commitResult.reusedChunkCount()).isEqualTo(3);
        assertThat(commitResult.embeddedChunkCount()).isEqualTo(2);

        verify(indexStore).replace(eq(document), eq(false), anyInt(), any());
        verify(store).markSucceeded(taskId, workerId, lease, 5, 3, 2);
    }

    @Test
    void workerGracefullyHandlesStaleLeaseCommitRejection() throws Exception {
        DocumentIndexTaskStore workerStore = mock(DocumentIndexTaskStore.class);
        DocumentIndexTaskExecutor workerExecutor = mock(DocumentIndexTaskExecutor.class);
        IndexRetryPolicy retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                workerStore, workerExecutor, retryPolicy, "worker-stale-test", 1);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-fenced-stale")
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId("worker-stale-test")
                .leaseVersion(1L)
                .retryCount(0)
                .maxRetries(3)
                .build();

        when(workerStore.claimNextTask("worker-stale-test")).thenReturn(java.util.Optional.of(task));
        when(workerExecutor.execute(task)).thenThrow(new StaleDocumentIndexLeaseException("Stale lease on commit"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        // Discarded without marking failed or retry_wait (since another worker took over)
        verify(workerStore, never()).markFailed(anyString(), anyString(), anyLong(), anyString(), anyInt());
        verify(workerStore, never()).markRetryWait(anyString(), anyString(), anyLong(), anyString(), any(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).isEmpty();

        worker.destroy();
    }
}
