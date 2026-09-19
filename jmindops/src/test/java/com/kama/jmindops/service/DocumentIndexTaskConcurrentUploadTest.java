package com.kama.jmindops.service;

import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskConcurrentUploadTest {

    private DocumentMapper documentMapper;
    private DocumentIndexTaskStore taskStore;
    private DocumentStorageService storageService;
    private DocumentIndexTaskWorker taskWorker;
    private DocumentIndexTaskEnqueueService enqueueService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        taskStore = mock(DocumentIndexTaskStore.class);
        storageService = mock(DocumentStorageService.class);
        taskWorker = mock(DocumentIndexTaskWorker.class);

        enqueueService = new DocumentIndexTaskEnqueueService(
                taskStore, documentMapper, storageService, taskWorker);
    }

    @Test
    void enqueueUploadAcquiresPostgresAdvisoryLock() throws Exception {
        String kbId = "kb-concurrent";
        String sourceKey = "guide.md";

        when(documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(null);
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.empty());

        DocumentIndexTaskEnqueueService.EnqueueResult result = enqueueService.enqueue(
                kbId, "guide.md", "md", 100L, "docs/guide.md", "hash-1", sourceKey, "fp-1", 3, null);

        assertThat(result).isNotNull();
        assertThat(result.action()).isEqualTo("CREATED");
        // Verifies advisory lock is acquired on entry
        verify(taskStore).lockDocument(kbId, sourceKey);
        verify(taskStore).createTask(any(DocumentIndexTask.class));
    }

    @Test
    void concurrentUploadReusesInflightActiveTaskWithSameContentHash() throws Exception {
        String kbId = "kb-concurrent";
        String sourceKey = "guide.md";
        String contentHash = "hash-identical";
        String fp = "fp-1";

        DocumentIndexTask activeTask = DocumentIndexTask.builder()
                .id("task-active-1")
                .kbId(kbId)
                .documentId("doc-concurrent-1")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.RUNNING)
                .contentHash(contentHash)
                .sourceKey(sourceKey)
                .indexFingerprint(fp)
                .build();

        when(documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(null);
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.of(activeTask));

        DocumentIndexTaskEnqueueService.EnqueueResult result = enqueueService.enqueue(
                kbId, "guide.md", "md", 100L, "docs/duplicate.md", contentHash, sourceKey, fp, 3, null);

        assertThat(result.documentId()).isEqualTo("doc-concurrent-1");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.action()).isEqualTo("UNCHANGED");

        // Should clean up the redundant newly uploaded file
        verify(storageService).deleteFile("docs/duplicate.md");
        // Should NOT create a duplicate task
        verify(taskStore, never()).createTask(any());
    }

    @Test
    void concurrentUploadCreatesNewVersionWhenContentChanges() throws Exception {
        String kbId = "kb-concurrent";
        String sourceKey = "guide.md";
        String newHash = "hash-v2";
        String fp = "fp-1";

        Document existingDoc = Document.builder()
                .id("doc-concurrent-2")
                .kbId(kbId)
                .sourceKey(sourceKey)
                .contentHash("hash-v1")
                .indexVersion(1)
                .indexFingerprint(fp)
                .indexStatus("READY")
                .metadata("{\"filePath\":\"docs/v1.md\"}")
                .build();

        when(documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(existingDoc);
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.empty());

        DocumentIndexTaskEnqueueService.EnqueueResult result = enqueueService.enqueue(
                kbId, "guide.md", "md", 150L, "docs/v2.md", newHash, sourceKey, fp, 3, null);

        assertThat(result.documentId()).isEqualTo("doc-concurrent-2");
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.action()).isEqualTo("UPDATED");

        verify(taskStore).createTask(any(DocumentIndexTask.class));
    }

    @Test
    void livePostgresAdvisoryLockSerializesConcurrentUploads() throws Exception {
        int targetPort = -1;
        for (int port : new int[]{5432, 5433}) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                targetPort = port;
                break;
            } catch (Exception ignored) {}
        }

        org.junit.jupiter.api.Assumptions.assumeTrue(targetPort > 0, "Live PostgreSQL database required on port 5432 or 5433");

        String username = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");
        String db = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + db;

        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbcUrl, username, password);
        org.springframework.jdbc.datasource.DataSourceTransactionManager txManager =
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        org.springframework.transaction.support.TransactionTemplate txTemplate =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        DocumentIndexTaskStore realStore = new DocumentIndexTaskStore(new org.springframework.jdbc.core.JdbcTemplate(ds));

        String kbId = java.util.UUID.randomUUID().toString();
        String sourceKey = "concurrent_test.md";

        java.util.concurrent.atomic.AtomicInteger runningInLock = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicBoolean overlapDetected = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    txTemplate.executeWithoutResult(status -> {
                        realStore.lockDocument(kbId, sourceKey);
                        int count = runningInLock.incrementAndGet();
                        if (count > 1) {
                            overlapDetected.set(true);
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {}
                        runningInLock.decrementAndGet();
                    });
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(overlapDetected.get()).as("Advisory lock must prevent overlapping critical sections").isFalse();
    }
}
