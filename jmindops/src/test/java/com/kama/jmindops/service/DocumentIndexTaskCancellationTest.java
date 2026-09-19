package com.kama.jmindops.service;

import com.kama.jmindops.exception.TaskCancelledException;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskCancellationTest {

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
    void requestCancellationByDocumentIdUpdatesCancelRequestedFlag() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore taskStore = new DocumentIndexTaskStore(jdbcTemplate);

        when(jdbcTemplate.update(anyString(), eq("doc-cancel-1"))).thenReturn(1);

        int updated = taskStore.requestCancellationByDocumentId("doc-cancel-1");

        assertThat(updated).isEqualTo(2);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(anyString(), eq("doc-cancel-1"));
    }

    @Test
    void commitPreparedIndexThrowsTaskCancelledExceptionWhenCancelRequested() {
        String taskId = "task-cancelled-commit";
        String workerId = "worker-1";
        long lease = 1L;

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(lease)
                .build();

        DocumentIndexTask lockedTask = DocumentIndexTask.builder()
                .id(taskId)
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId(workerId)
                .leaseVersion(lease)
                .cancelRequested(true) // cancellation was requested
                .build();

        when(store.findAndLockForCommit(taskId)).thenReturn(lockedTask);

        Document document = Document.builder().id("doc-1").kbId("kb-1").indexVersion(1).build();
        IncrementalDocumentIndexService.IndexResult result = new IncrementalDocumentIndexService.IndexResult(5, 3, 2);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                document, false, List.of(), result);

        assertThatThrownBy(() -> commitService.commit(task, workerId, lease, prepared))
                .isInstanceOf(TaskCancelledException.class)
                .hasMessageContaining("Task has been cancelled");

        verify(indexStore, never()).replace(any(), anyBoolean(), any());
        verify(store, never()).markSucceeded(anyString(), anyString(), anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void workerHandlesTaskCancelledExceptionAndMarksCancelled() throws Exception {
        DocumentIndexTaskStore workerStore = mock(DocumentIndexTaskStore.class);
        DocumentIndexTaskExecutor workerExecutor = mock(DocumentIndexTaskExecutor.class);
        IndexRetryPolicy retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                workerStore, workerExecutor, retryPolicy, "worker-cancel-test", 1);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-cancel-run")
                .filePath("docs/tmp-file.md")
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId("worker-cancel-test")
                .leaseVersion(1L)
                .cancelRequested(true)
                .retryCount(0)
                .maxRetries(3)
                .build();

        when(workerStore.claimNextTask("worker-cancel-test")).thenReturn(java.util.Optional.of(task));
        when(workerExecutor.execute(task)).thenThrow(new TaskCancelledException("task-cancel-run"));

        boolean processed = worker.processNextTask();

        assertThat(processed).isTrue();
        verify(workerStore).markCancelled(eq("task-cancel-run"), eq("worker-cancel-test"), eq(1L), contains("用户取消"));
        verify(workerStore, never()).markFailed(anyString(), anyString(), anyLong(), anyString(), anyInt());
        verify(workerStore, never()).markRetryWait(anyString(), anyString(), anyLong(), anyString(), any(), anyInt());
        assertThat(worker.getActiveTasksSnapshot()).isEmpty();

        worker.destroy();
    }
}
