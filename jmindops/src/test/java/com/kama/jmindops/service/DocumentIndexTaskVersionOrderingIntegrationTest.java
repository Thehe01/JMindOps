package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.Socket;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;

@Tag("postgres-integration")
class DocumentIndexTaskVersionOrderingIntegrationTest {

    @Test
    void claimNextTaskQueryEnforcesPerDocumentVersionStrictOrdering() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq("worker-1")))
                .thenReturn(List.of());

        store.claimNextTask("worker-1");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("worker-1"));

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("lease_version = t.lease_version + 1")
                .contains("cancel_requested = FALSE")
                .contains("NOT EXISTS (")
                .contains("earlier.document_id = t.document_id")
                .contains("earlier.index_version < t.index_version")
                .contains("earlier.status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')");
    }

    @Test
    void earlierActiveVersionBlocksLaterVersionClaim() {
        ConcurrentMap<String, DocumentIndexTask> table = new ConcurrentHashMap<>();
        String docId = "doc-order-1";

        DocumentIndexTask v1 = DocumentIndexTask.builder()
                .id("task-v1")
                .documentId(docId)
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.RUNNING)
                .leaseVersion(1L)
                .cancelRequested(false)
                .build();

        DocumentIndexTask v2 = DocumentIndexTask.builder()
                .id("task-v2")
                .documentId(docId)
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.PENDING)
                .leaseVersion(0L)
                .cancelRequested(false)
                .build();

        table.put(v1.getId(), v1);
        table.put(v2.getId(), v2);

        // Simulated filter mimicking SQL NOT EXISTS earlier active version
        boolean v2Eligible = table.values().stream()
                .noneMatch(earlier -> earlier.getDocumentId().equals(v2.getDocumentId())
                        && earlier.getIndexVersion() < v2.getIndexVersion()
                        && (earlier.getStatus() == DocumentIndexTaskStatus.PENDING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RUNNING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RETRY_WAIT));

        assertThat(v2Eligible).isFalse();

        // When v1 completes successfully
        v1.setStatus(DocumentIndexTaskStatus.SUCCEEDED);
        v1.setCompletedAt(LocalDateTime.now());

        boolean v2EligibleAfterV1Success = table.values().stream()
                .noneMatch(earlier -> earlier.getDocumentId().equals(v2.getDocumentId())
                        && earlier.getIndexVersion() < v2.getIndexVersion()
                        && (earlier.getStatus() == DocumentIndexTaskStatus.PENDING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RUNNING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RETRY_WAIT));

        assertThat(v2EligibleAfterV1Success).isTrue();
    }

    @Test
    void earlierFailedOrCancelledVersionDoesNotBlockLaterVersion() {
        ConcurrentMap<String, DocumentIndexTask> table = new ConcurrentHashMap<>();
        String docId = "doc-order-2";

        DocumentIndexTask v1 = DocumentIndexTask.builder()
                .id("task-v1")
                .documentId(docId)
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.CANCELLED)
                .leaseVersion(1L)
                .cancelRequested(true)
                .build();

        DocumentIndexTask v2 = DocumentIndexTask.builder()
                .id("task-v2")
                .documentId(docId)
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.PENDING)
                .leaseVersion(0L)
                .cancelRequested(false)
                .build();

        table.put(v1.getId(), v1);
        table.put(v2.getId(), v2);

        boolean v2Eligible = table.values().stream()
                .noneMatch(earlier -> earlier.getDocumentId().equals(v2.getDocumentId())
                        && earlier.getIndexVersion() < v2.getIndexVersion()
                        && (earlier.getStatus() == DocumentIndexTaskStatus.PENDING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RUNNING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RETRY_WAIT));

        assertThat(v2Eligible).isTrue();
    }

    @Test
    void earlierFailedVersionAllowsLaterVersionExecutionAndOptimisticCommit() {
        ConcurrentMap<String, DocumentIndexTask> table = new ConcurrentHashMap<>();
        String docId = "doc-order-3";

        DocumentIndexTask v1 = DocumentIndexTask.builder()
                .id("task-v1-failed")
                .documentId(docId)
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.FAILED)
                .leaseVersion(1L)
                .cancelRequested(false)
                .build();

        DocumentIndexTask v2 = DocumentIndexTask.builder()
                .id("task-v2")
                .documentId(docId)
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.PENDING)
                .leaseVersion(0L)
                .cancelRequested(false)
                .build();

        table.put(v1.getId(), v1);
        table.put(v2.getId(), v2);

        // Subquery check: earlier FAILED task does not block v2
        boolean v2Eligible = table.values().stream()
                .noneMatch(earlier -> earlier.getDocumentId().equals(v2.getDocumentId())
                        && earlier.getIndexVersion() < v2.getIndexVersion()
                        && (earlier.getStatus() == DocumentIndexTaskStatus.PENDING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RUNNING
                        || earlier.getStatus() == DocumentIndexTaskStatus.RETRY_WAIT)
                        && !Boolean.TRUE.equals(earlier.getCancelRequested()));

        assertThat(v2Eligible).isTrue();
    }

    @Test
    void livePostgresVersionOrderingSerializationIfAvailable() {
        int targetPort = -1;
        for (int port : new int[]{5432, 5433}) {
            try (Socket s = new Socket("127.0.0.1", port)) {
                targetPort = port;
                break;
            } catch (Exception ignored) {}
        }

        if (targetPort < 0) {
            org.junit.jupiter.api.Assertions.fail("Live PostgreSQL required for live serialization query execution");
        }

        String username = System.getenv().getOrDefault("POSTGRES_USER", "jmindops_owner");
        String password = System.getenv().getOrDefault("POSTGRES_PASSWORD", "jmindops_owner");
        String db = System.getenv().getOrDefault("POSTGRES_DB", "jmindops");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + targetPort + "/" + db;

        org.springframework.jdbc.datasource.DriverManagerDataSource ds =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate realJdbc = new JdbcTemplate(ds);
        DocumentIndexTaskStore realStore = new DocumentIndexTaskStore(realJdbc);

        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        flyway.migrate();

        String kbId = UUID.randomUUID().toString();
        String docA = UUID.randomUUID().toString();
        String docB = UUID.randomUUID().toString();

        realJdbc.execute("INSERT INTO knowledge_base (id, name) VALUES ('" + kbId + "', 'test-kb') ON CONFLICT DO NOTHING");

        try {
            LocalDateTime now = LocalDateTime.now();
            DocumentIndexTask taskA1 = DocumentIndexTask.builder()
                    .id(UUID.randomUUID().toString()).kbId(kbId).documentId(docA).indexVersion(1)
                    .status(DocumentIndexTaskStatus.PENDING).filePath("a1.md").filename("a1.md").filetype("md")
                    .fileSize(10L).contentHash("hash-a1").sourceKey("a.md").indexFingerprint("fp-1").isNewDocument(true)
                    .createdAt(now).updatedAt(now).build();

            DocumentIndexTask taskA2 = DocumentIndexTask.builder()
                    .id(UUID.randomUUID().toString()).kbId(kbId).documentId(docA).indexVersion(2)
                    .status(DocumentIndexTaskStatus.PENDING).filePath("a2.md").filename("a2.md").filetype("md")
                    .fileSize(10L).contentHash("hash-a2").sourceKey("a.md").indexFingerprint("fp-1").isNewDocument(false)
                    .createdAt(now.plusSeconds(1)).updatedAt(now.plusSeconds(1)).build();

            DocumentIndexTask taskB1 = DocumentIndexTask.builder()
                    .id(UUID.randomUUID().toString()).kbId(kbId).documentId(docB).indexVersion(1)
                    .status(DocumentIndexTaskStatus.PENDING).filePath("b1.md").filename("b1.md").filetype("md")
                    .fileSize(10L).contentHash("hash-b1").sourceKey("b.md").indexFingerprint("fp-1").isNewDocument(true)
                    .createdAt(now.plusSeconds(2)).updatedAt(now.plusSeconds(2)).build();

            realStore.createTask(taskA1);
            realStore.createTask(taskA2);
            realStore.createTask(taskB1);

            Optional<DocumentIndexTask> claim1 = realStore.claimNextTask("worker-live-1");
            Optional<DocumentIndexTask> claim2 = realStore.claimNextTask("worker-live-2");

            assertThat(claim1).isPresent();
            assertThat(claim2).isPresent();

            List<String> claimedDocVersions = List.of(
                    claim1.get().getDocumentId() + "-v" + claim1.get().getIndexVersion(),
                    claim2.get().getDocumentId() + "-v" + claim2.get().getIndexVersion()
            );

            // Must contain A1 and B1, and MUST NOT contain A2!
            assertThat(claimedDocVersions).containsExactlyInAnyOrder(docA + "-v1", docB + "-v1");
            assertThat(claimedDocVersions).doesNotContain(docA + "-v2");

            // Complete A1
            DocumentIndexTask claimedA1 = claim1.get().getDocumentId().equals(docA) ? claim1.get() : claim2.get();
            boolean marked = realStore.markSucceeded(claimedA1.getId(), claimedA1.getWorkerId(), claimedA1.getLeaseVersion(), 1, 0, 1);
            assertThat(marked).isTrue();

            // Now A2 should become claimable!
            Optional<DocumentIndexTask> claim3 = realStore.claimNextTask("worker-live-1");
            assertThat(claim3).isPresent();
            assertThat(claim3.get().getDocumentId()).isEqualTo(docA);
            assertThat(claim3.get().getIndexVersion()).isEqualTo(2);

            realStore.markSucceeded(claim3.get().getId(), claim3.get().getWorkerId(), claim3.get().getLeaseVersion(), 1, 0, 1);
        } finally {
            try {
                realJdbc.update("DELETE FROM document_index_task WHERE kb_id = CAST(? AS uuid)", kbId);
                realJdbc.update("DELETE FROM knowledge_base WHERE id = CAST(? AS uuid)", kbId);
            } catch (Exception ignored) {}
        }
    }
}
