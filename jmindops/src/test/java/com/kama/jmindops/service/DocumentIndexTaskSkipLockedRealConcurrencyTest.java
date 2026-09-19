package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentIndexTaskSkipLockedRealConcurrencyTest {

    @Test
    void multiThreadedSimulatedWorkersNeverClaimSameTask() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        int totalTasks = 20;
        int workerCount = 8;

        // Shared thread-safe task queue simulating PostgreSQL SKIP LOCKED
        List<DocumentIndexTask> poolTasks = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < totalTasks; i++) {
            poolTasks.add(DocumentIndexTask.builder()
                    .id("task-" + i)
                    .documentId("doc-" + i)
                    .indexVersion(1)
                    .status(DocumentIndexTaskStatus.PENDING)
                    .leaseVersion(0L)
                    .cancelRequested(false)
                    .build());
        }

        when(jdbcTemplate.query(contains("FOR UPDATE SKIP LOCKED"), any(RowMapper.class), anyString()))
                .thenAnswer(invocation -> {
                    String workerId = invocation.getArgument(2);
                    synchronized (poolTasks) {
                        for (DocumentIndexTask t : poolTasks) {
                            if (t.getStatus() == DocumentIndexTaskStatus.PENDING) {
                                t.setStatus(DocumentIndexTaskStatus.RUNNING);
                                t.setWorkerId(workerId);
                                t.setLeaseVersion(t.getLeaseVersion() + 1);
                                return List.of(DocumentIndexTask.builder()
                                        .id(t.getId())
                                        .documentId(t.getDocumentId())
                                        .indexVersion(t.getIndexVersion())
                                        .status(t.getStatus())
                                        .workerId(t.getWorkerId())
                                        .leaseVersion(t.getLeaseVersion())
                                        .cancelRequested(t.getCancelRequested())
                                        .build());
                            }
                        }
                    }
                    return List.of();
                });

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(workerCount);
        Set<String> claimedTaskIds = ConcurrentHashMap.newKeySet();
        AtomicInteger totalClaimed = new AtomicInteger(0);

        for (int w = 0; w < workerCount; w++) {
            final String workerId = "concurrent-worker-" + w;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (true) {
                        Optional<DocumentIndexTask> claimed = store.claimNextTask(workerId);
                        if (claimed.isEmpty()) {
                            break;
                        }
                        boolean isNew = claimedTaskIds.add(claimed.get().getId());
                        assertThat(isNew).as("Task claimed by multiple workers! id=" + claimed.get().getId()).isTrue();
                        totalClaimed.incrementAndGet();
                    }
                } catch (Exception e) {
                    // unexpected error
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        finishLatch.await();
        executor.shutdown();

        assertThat(totalClaimed.get()).isEqualTo(totalTasks);
        assertThat(claimedTaskIds).hasSize(totalTasks);
    }

    @Test
    void livePostgresRealSkipLockedConcurrency() throws Exception {
        int targetPort = -1;
        for (int port : new int[]{5432, 5433}) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                targetPort = port;
                break;
            } catch (Exception ignored) {}
        }

        Assumptions.assumeTrue(targetPort > 0, "Live PostgreSQL database required on port 5432 or 5433");

        String username = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");
        String db = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + db;

        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate realJdbc = new JdbcTemplate(ds);

        String kbId = java.util.UUID.randomUUID().toString();
        try {
            realJdbc.execute("INSERT INTO knowledge_base (id, name) VALUES ('" + kbId + "', 'test-skip-locked-kb') ON CONFLICT DO NOTHING");
        } catch (Exception ignored) {
            return;
        }

        int totalTasks = 10;
        int workerCount = 4;
        List<String> createdTaskIds = new ArrayList<>();

        try {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            for (int i = 0; i < totalTasks; i++) {
                String taskId = java.util.UUID.randomUUID().toString();
                String docId = java.util.UUID.randomUUID().toString();
                createdTaskIds.add(taskId);
                DocumentIndexTask task = DocumentIndexTask.builder()
                        .id(taskId).kbId(kbId).documentId(docId).indexVersion(1)
                        .status(DocumentIndexTaskStatus.PENDING).filePath("doc-" + i + ".md")
                        .filename("doc-" + i + ".md").filetype("md").fileSize(20L)
                        .contentHash("hash-sl-" + i).sourceKey("doc-" + i + ".md").indexFingerprint("fp-sl")
                        .isNewDocument(true).createdAt(now.plusNanos(i * 1000L)).updatedAt(now.plusNanos(i * 1000L))
                        .build();
                new DocumentIndexTaskStore(realJdbc).createTask(task);
            }

            ExecutorService executor = Executors.newFixedThreadPool(workerCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch finishLatch = new CountDownLatch(workerCount);
            Set<String> claimedTaskIds = ConcurrentHashMap.newKeySet();
            AtomicInteger totalClaimed = new AtomicInteger(0);

            for (int w = 0; w < workerCount; w++) {
                final String workerId = "real-skip-worker-" + w;
                executor.submit(() -> {
                    DocumentIndexTaskStore workerStore = new DocumentIndexTaskStore(new JdbcTemplate(ds));
                    try {
                        startLatch.await();
                        while (true) {
                            Optional<DocumentIndexTask> claimed = workerStore.claimNextTask(workerId);
                            if (claimed.isEmpty()) {
                                break;
                            }
                            boolean isNew = claimedTaskIds.add(claimed.get().getId());
                            assertThat(isNew).as("Real PostgreSQL SKIP LOCKED allowed duplicate claim: id=" + claimed.get().getId()).isTrue();
                            totalClaimed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // error
                    } finally {
                        finishLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            finishLatch.await();
            executor.shutdown();

            assertThat(totalClaimed.get()).isEqualTo(totalTasks);
            assertThat(claimedTaskIds).hasSize(totalTasks);
            assertThat(claimedTaskIds).containsExactlyInAnyOrderElementsOf(createdTaskIds);
        } finally {
            try {
                realJdbc.update("DELETE FROM document_index_task WHERE kb_id = CAST(? AS uuid)", kbId);
                realJdbc.update("DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)", kbId);
            } catch (Exception ignored) {}
        }
    }
}
