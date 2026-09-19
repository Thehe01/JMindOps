package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskRecoveryBackoffTest {

    @Test
    void exponentialBackoffCalculationFollowsPolicy() {
        IndexRetryPolicy policy = new IndexRetryPolicy(5, 2, 2.0, 60, 120);

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.calculateBackoff(3)).isEqualTo(Duration.ofSeconds(16));
        assertThat(policy.calculateBackoff(4)).isEqualTo(Duration.ofSeconds(32));
        assertThat(policy.calculateBackoff(5)).isEqualTo(Duration.ofSeconds(60)); // capped at 60s
        assertThat(policy.calculateBackoff(10)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void schemeASqlQueryCalculatesExponentialBackoffInPostgres() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(120L), eq(10), eq(2L)))
                .thenReturn(List.of());

        store.recoverStaleRunningTasks(Duration.ofSeconds(120), Duration.ofSeconds(2), 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(120L), eq(10), eq(2L));

        String sql = sqlCaptor.getValue();
        assertThat(sql)
                .contains("POWER(2.0, stale.retry_count)")
                .contains("LEAST(? * POWER(2.0, stale.retry_count), 60.0)")
                .contains("INTERVAL '1 second'");
    }

    @Test
    void schemeBRecoveryUpdatesBackoffAndHandlesCancelledTasks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DocumentIndexTaskStore store = new DocumentIndexTaskStore(jdbcTemplate);
        IndexRetryPolicy policy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        DocumentIndexTask staleTask1 = DocumentIndexTask.builder()
                .id("stale-1")
                .status(DocumentIndexTaskStatus.RUNNING)
                .retryCount(0)
                .maxRetries(3)
                .cancelRequested(false)
                .build();

        DocumentIndexTask staleTaskCancelled = DocumentIndexTask.builder()
                .id("stale-cancelled")
                .status(DocumentIndexTaskStatus.RUNNING)
                .retryCount(1)
                .maxRetries(3)
                .cancelRequested(true)
                .build();

        // Stale query returns 2 tasks
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(120L), eq(10)))
                .thenReturn(List.of(staleTask1, staleTaskCancelled));

        List<DocumentIndexTask> recovered = store.recoverStaleRunningTasks(Duration.ofSeconds(120), policy, 10);

        assertThat(recovered).hasSize(2);

        DocumentIndexTask res1 = recovered.get(0);
        assertThat(res1.getStatus()).isEqualTo(DocumentIndexTaskStatus.RETRY_WAIT);
        assertThat(res1.getRetryCount()).isEqualTo(1);
        assertThat(res1.getNextRetryAt()).isNotNull();

        DocumentIndexTask resCancelled = recovered.get(1);
        assertThat(resCancelled.getStatus()).isEqualTo(DocumentIndexTaskStatus.CANCELLED);
        assertThat(resCancelled.getNextRetryAt()).isNull();
    }
}
