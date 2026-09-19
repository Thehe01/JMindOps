package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskRecoveryTest {

    private DocumentIndexTaskStore store;
    private DocumentIndexTaskWorker worker;
    private IndexRetryPolicy retryPolicy;
    private DocumentIndexTaskRecovery recovery;

    @BeforeEach
    void setUp() {
        store = mock(DocumentIndexTaskStore.class);
        worker = mock(DocumentIndexTaskWorker.class);
        retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);
        recovery = new DocumentIndexTaskRecovery(store, worker, retryPolicy, 10);
    }

    @Test
    void heartbeatsLocallyOwnedTasksBeforeRecovery() {
        when(store.recoverStaleRunningTasks(any(Duration.class), any(IndexRetryPolicy.class), eq(10)))
                .thenReturn(List.of());

        recovery.recover();

        verify(worker).heartbeatActiveTasks();
        verify(store).recoverStaleRunningTasks(Duration.ofSeconds(120), retryPolicy, 10);
        verify(worker, never()).triggerAsync();
    }

    @Test
    void recoversStaleRunningTasksAndTriggersWorker() {
        DocumentIndexTask recoveredTask = DocumentIndexTask.builder()
                .id("stale-task-1")
                .status(DocumentIndexTaskStatus.RETRY_WAIT)
                .retryCount(1)
                .maxRetries(3)
                .build();

        when(store.recoverStaleRunningTasks(Duration.ofSeconds(120), retryPolicy, 10))
                .thenReturn(List.of(recoveredTask));

        recovery.recover();

        verify(worker).heartbeatActiveTasks();
        verify(store).recoverStaleRunningTasks(Duration.ofSeconds(120), retryPolicy, 10);
        verify(worker).triggerAsync();
    }
}
