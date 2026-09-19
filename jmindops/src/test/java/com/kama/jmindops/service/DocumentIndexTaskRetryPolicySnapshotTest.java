package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskRetryPolicySnapshotTest {

    @Test
    void workerHonorsSnapshotMaxRetriesFromTaskRatherThanGlobalPolicy() throws Exception {
        DocumentIndexTaskStore store = mock(DocumentIndexTaskStore.class);
        DocumentIndexTaskExecutor executor = mock(DocumentIndexTaskExecutor.class);
        // Global policy configured with maxRetries = 10
        IndexRetryPolicy globalPolicy = new IndexRetryPolicy(10, 2, 2.0, 60, 120);

        DocumentIndexTaskWorker worker = new DocumentIndexTaskWorker(
                store, executor, globalPolicy, "worker-snapshot-test", 1);

        // Task created earlier with maxRetries snapshot = 2
        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-snapshot-1")
                .status(DocumentIndexTaskStatus.RUNNING)
                .leaseVersion(1L)
                .retryCount(2)
                .maxRetries(2) // task's own snapshot
                .build();

        when(store.claimNextTask("worker-snapshot-test")).thenReturn(Optional.of(task));
        when(executor.execute(task)).thenThrow(new IOException("Transient error"));

        worker.processNextTask();

        // Since retryCount (2) >= task.maxRetries (2), should be marked FAILED, not RETRY_WAIT!
        verify(store).markFailed(eq("task-snapshot-1"), eq("worker-snapshot-test"), eq(1L), eq("Transient error"), eq(3));
        verify(store, never()).markRetryWait(anyString(), anyString(), anyLong(), anyString(), any(), anyInt());

        worker.destroy();
    }

    @Test
    void canRetryOverloadRespectsExplicitMaxRetries() {
        IndexRetryPolicy policy = new IndexRetryPolicy(5, 2, 2.0, 60, 120);

        assertThat(policy.canRetry(1, 3, new IOException("fail"))).isTrue();
        assertThat(policy.canRetry(2, 3, new IOException("fail"))).isTrue();
        assertThat(policy.canRetry(3, 3, new IOException("fail"))).isFalse();
        assertThat(policy.canRetry(4, 3, new IOException("fail"))).isFalse();

        // Non-retryable error always returns false regardless of retries
        assertThat(policy.canRetry(0, 3, new BizException("没有可索引的文本内容"))).isFalse();
    }
}
