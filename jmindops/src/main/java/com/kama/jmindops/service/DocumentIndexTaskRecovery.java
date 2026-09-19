package com.kama.jmindops.service;

import com.kama.jmindops.model.entity.DocumentIndexTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class DocumentIndexTaskRecovery {
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexTaskRecovery.class);

    private final DocumentIndexTaskStore store;
    private final DocumentIndexTaskWorker worker;
    private final IndexRetryPolicy retryPolicy;
    private final int batchSize;

    public DocumentIndexTaskRecovery(
            DocumentIndexTaskStore store,
            DocumentIndexTaskWorker worker,
            IndexRetryPolicy retryPolicy,
            @Value("${app.document-index.batch-size:10}") int batchSize
    ) {
        this.store = store;
        this.worker = worker;
        this.retryPolicy = retryPolicy;
        this.batchSize = Math.max(1, Math.min(batchSize, 100));
    }

    @Scheduled(
            initialDelayString = "${app.document-index.recovery-initial-delay-ms:10000}",
            fixedDelayString = "${app.document-index.recovery-fixed-delay-ms:10000}"
    )
    public void recover() {
        worker.heartbeatActiveTasks();
        recoverStaleRunningTasks();
    }

    public List<DocumentIndexTask> recoverStaleRunningTasks() {
        Duration backoff = retryPolicy.calculateBackoff(0);
        List<DocumentIndexTask> recovered = store.recoverStaleRunningTasks(
                retryPolicy.getRunningTimeout(), backoff, batchSize);
        if (!recovered.isEmpty()) {
            log.warn("Recovered {} stale RUNNING document index tasks", recovered.size());
            worker.triggerAsync();
        }
        return recovered;
    }
}
