package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskFencingTokenTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentIndexTaskStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new DocumentIndexTaskStore(jdbcTemplate);
    }

    @Test
    void claimNextTaskIncrementsLeaseVersionInSql() {
        DocumentIndexTask claimedTask = DocumentIndexTask.builder()
                .id("task-fenced-1")
                .status(DocumentIndexTaskStatus.RUNNING)
                .workerId("worker-fencing")
                .leaseVersion(1L)
                .build();

        when(jdbcTemplate.query(contains("t.lease_version + 1"), any(RowMapper.class), eq("worker-fencing")))
                .thenReturn(List.of(claimedTask));

        Optional<DocumentIndexTask> result = store.claimNextTask("worker-fencing");

        assertThat(result).isPresent();
        assertThat(result.get().getLeaseVersion()).isEqualTo(1L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq("worker-fencing"));
        assertThat(sqlCaptor.getValue())
                .contains("lease_version = t.lease_version + 1")
                .contains("worker_id = ?");
    }

    @Test
    void touchHeartbeatWithLeaseChecksLeaseVersion() {
        when(jdbcTemplate.update(contains("lease_version = ?"), eq("task-1"), eq("worker-1"), eq(2L)))
                .thenReturn(1);

        boolean ok = store.touchHeartbeat("task-1", "worker-1", 2L);
        assertThat(ok).isTrue();

        // Stale lease version (update returns 0 affected rows)
        when(jdbcTemplate.update(contains("lease_version = ?"), eq("task-1"), eq("worker-1"), eq(1L)))
                .thenReturn(0);

        boolean stale = store.touchHeartbeat("task-1", "worker-1", 1L);
        assertThat(stale).isFalse();
    }

    @Test
    void markSucceededWithLeaseChecksLeaseVersion() {
        when(jdbcTemplate.update(contains("lease_version = ?"), eq(10), eq(6), eq(4), eq("task-1"), eq(3L)))
                .thenReturn(1);

        boolean ok = store.markSucceeded("task-1", 3L, 10, 6, 4);
        assertThat(ok).isTrue();

        // Stale lease rejected
        when(jdbcTemplate.update(contains("lease_version = ?"), eq(10), eq(6), eq(4), eq("task-1"), eq(2L)))
                .thenReturn(0);

        boolean stale = store.markSucceeded("task-1", 2L, 10, 6, 4);
        assertThat(stale).isFalse();

        // With workerId and leaseVersion binding
        when(jdbcTemplate.update(contains("worker_id = ?"), eq(10), eq(6), eq(4), eq("task-1"), eq("worker-1"), eq(3L)))
                .thenReturn(1);
        boolean okWorker = store.markSucceeded("task-1", "worker-1", 3L, 10, 6, 4);
        assertThat(okWorker).isTrue();

        when(jdbcTemplate.update(contains("worker_id = ?"), eq(10), eq(6), eq(4), eq("task-1"), eq("worker-1"), eq(2L)))
                .thenReturn(0);
        boolean staleWorker = store.markSucceeded("task-1", "worker-1", 2L, 10, 6, 4);
        assertThat(staleWorker).isFalse();
    }

    @Test
    void markRetryWaitWithLeaseChecksLeaseVersion() {
        LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(4);
        when(jdbcTemplate.update(contains("lease_version = ?"), eq(1), eq(nextRetry), eq("timeout"), eq("task-1"), eq(2L)))
                .thenReturn(1);

        boolean ok = store.markRetryWait("task-1", 2L, "timeout", nextRetry, 1);
        assertThat(ok).isTrue();

        when(jdbcTemplate.update(contains("lease_version = ?"), eq(1), eq(nextRetry), eq("timeout"), eq("task-1"), eq(1L)))
                .thenReturn(0);

        boolean stale = store.markRetryWait("task-1", 1L, "timeout", nextRetry, 1);
        assertThat(stale).isFalse();
    }

    @Test
    void markFailedWithLeaseChecksLeaseVersion() {
        when(jdbcTemplate.update(contains("lease_version = ?"), eq(3), eq("exhausted"), eq("task-1"), eq(2L)))
                .thenReturn(1);

        boolean ok = store.markFailed("task-1", 2L, "exhausted", 3);
        assertThat(ok).isTrue();

        when(jdbcTemplate.update(contains("lease_version = ?"), eq(3), eq("exhausted"), eq("task-1"), eq(1L)))
                .thenReturn(0);

        boolean stale = store.markFailed("task-1", 1L, "exhausted", 3);
        assertThat(stale).isFalse();
    }

    @Test
    void markCancelledWithLeaseChecksLeaseVersion() {
        when(jdbcTemplate.update(contains("lease_version = ?"), eq("task-1"), eq(2L)))
                .thenReturn(1);

        boolean ok = store.markCancelled("task-1", 2L);
        assertThat(ok).isTrue();

        when(jdbcTemplate.update(contains("lease_version = ?"), eq("task-1"), eq(1L)))
                .thenReturn(0);

        boolean stale = store.markCancelled("task-1", 1L);
        assertThat(stale).isFalse();
    }
}
