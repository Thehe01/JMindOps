package com.kama.jmindops.service;

import com.kama.jmindops.exception.StaleDocumentIndexLeaseException;
import com.kama.jmindops.exception.TaskCancelledException;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class DocumentIndexTaskWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexTaskWorker.class);

    private final DocumentIndexTaskStore store;
    private final DocumentIndexTaskExecutor executor;
    private final IndexRetryPolicy retryPolicy;
    private final String workerId;
    private final ConcurrentMap<String, DocumentIndexTask> activeTasks = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public DocumentIndexTaskWorker(
            DocumentIndexTaskStore store,
            DocumentIndexTaskExecutor executor,
            IndexRetryPolicy retryPolicy,
            @Value("${app.document-index.worker-threads:2}") int workerThreads
    ) {
        this(store, executor, retryPolicy, "worker-" + UUID.randomUUID(), workerThreads);
    }

    public DocumentIndexTaskWorker(
            DocumentIndexTaskStore store,
            DocumentIndexTaskExecutor executor,
            IndexRetryPolicy retryPolicy,
            String workerId,
            int workerThreads
    ) {
        this.store = store;
        this.executor = executor;
        this.retryPolicy = retryPolicy;
        this.workerId = workerId;
        this.workerExecutor = Executors.newFixedThreadPool(
                Math.max(1, workerThreads),
                new CustomizableThreadFactory("doc-index-worker-")
        );
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                new CustomizableThreadFactory("doc-index-heartbeat-")
        );
        this.heartbeatScheduler.scheduleWithFixedDelay(
                this::heartbeatActiveTasks, 3, 3, TimeUnit.SECONDS
        );
    }

    public String getWorkerId() {
        return workerId;
    }

    public Map<String, DocumentIndexTask> getActiveTasksSnapshot() {
        return Collections.unmodifiableMap(activeTasks);
    }

    public boolean processNextTask() {
        Optional<DocumentIndexTask> taskOpt = store.claimNextTask(workerId);
        if (taskOpt.isEmpty()) {
            return false;
        }

        DocumentIndexTask task = taskOpt.get();
        activeTasks.put(task.getId(), task);
        try {
            log.info("Worker [{}] claimed index task: taskId={}, documentId={}, version={}, lease={}",
                    workerId, task.getId(), task.getDocumentId(), task.getIndexVersion(), task.getLeaseVersion());

            DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

            if (task.getStatus() != DocumentIndexTaskStatus.SUCCEEDED) {
                if (task.getLeaseVersion() != null) {
                    boolean ok = store.markSucceeded(task.getId(), workerId, task.getLeaseVersion(),
                            result.chunkCount(), result.reusedChunkCount(), result.embeddedChunkCount());
                    if (!ok) {
                        throw new StaleDocumentIndexLeaseException("Failed to mark task SUCCEEDED (stale lease): taskId=" + task.getId()
                                + ", workerId=" + workerId + ", leaseVersion=" + task.getLeaseVersion());
                    }
                } else {
                    store.markSucceeded(task.getId(), result.chunkCount(), result.reusedChunkCount(), result.embeddedChunkCount());
                }
            }

            log.info("Document index task SUCCEEDED: taskId={}, documentId={}, chunks={}, reused={}, embedded={}",
                    task.getId(), task.getDocumentId(), result.chunkCount(), result.reusedChunkCount(), result.embeddedChunkCount());
        } catch (StaleDocumentIndexLeaseException e) {
            log.warn("Document index task lease expired or superseded: taskId={}, workerId={}, leaseVersion={}, reason=STALE_LEASE",
                    task.getId(), workerId, task.getLeaseVersion(), e);
        } catch (TaskCancelledException e) {
            log.info("Document index task was cancelled: taskId={}, workerId={}, leaseVersion={}",
                    task.getId(), workerId, task.getLeaseVersion());
            if (task.getLeaseVersion() != null) {
                store.markCancelled(task.getId(), workerId, task.getLeaseVersion(), "任务已被用户取消");
            }
            executor.cleanupTaskFile(task);
        } catch (Throwable t) {
            int currentRetry = task.getRetryCount() != null ? task.getRetryCount() : 0;
            int nextRetryCount = currentRetry + 1;
            int maxRetries = task.getMaxRetries() != null ? task.getMaxRetries() : retryPolicy.getMaxRetries();
            boolean canRetry = retryPolicy.canRetry(currentRetry, maxRetries, t);
            if (canRetry) {
                LocalDateTime nextRetryAt = retryPolicy.calculateNextRetryAt(currentRetry);
                if (task.getLeaseVersion() != null) {
                    boolean ok = store.markRetryWait(task.getId(), workerId, task.getLeaseVersion(), t.getMessage(), nextRetryAt, nextRetryCount);
                    if (!ok) {
                        log.warn("Failed to mark task RETRY_WAIT (lease expired): taskId={}, workerId={}, leaseVersion={}, reason=STALE_LEASE",
                                task.getId(), workerId, task.getLeaseVersion());
                        return true;
                    }
                } else {
                    store.markRetryWait(task.getId(), t.getMessage(), nextRetryAt, nextRetryCount);
                }
                log.warn("Document index task failed (retryable): taskId={}, retryCount={}, nextRetryAt={}, error={}",
                        task.getId(), nextRetryCount, nextRetryAt, t.getMessage());
            } else {
                if (task.getLeaseVersion() != null) {
                    boolean ok = store.markFailed(task.getId(), workerId, task.getLeaseVersion(), t.getMessage(), nextRetryCount);
                    if (!ok) {
                        log.warn("Failed to mark task FAILED (lease expired): taskId={}, workerId={}, leaseVersion={}, reason=STALE_LEASE",
                                task.getId(), workerId, task.getLeaseVersion());
                        return true;
                    }
                } else {
                    store.markFailed(task.getId(), t.getMessage(), nextRetryCount);
                }
                log.error("Document index task failed permanently: taskId={}, retryCount={}, error={}",
                        task.getId(), nextRetryCount, t.getMessage(), t);
            }
        } finally {
            activeTasks.remove(task.getId());
        }
        return true;
    }

    public void triggerAsync() {
        workerExecutor.submit(() -> {
            try {
                while (processNextTask()) {
                    // Continue claiming as long as tasks are immediately available
                }
            } catch (Exception e) {
                log.error("Error in worker execution loop: workerId={}", workerId, e);
            }
        });
    }

    public void heartbeatActiveTasks() {
        for (DocumentIndexTask active : activeTasks.values()) {
            try {
                if (active.getLeaseVersion() != null) {
                    boolean ok = store.touchHeartbeat(active.getId(), workerId, active.getLeaseVersion());
                    if (!ok) {
                        log.warn("Heartbeat rejected (lease expired or cancelled): taskId={}, workerId={}, leaseVersion={}",
                                active.getId(), workerId, active.getLeaseVersion());
                    }
                } else {
                    store.touchHeartbeat(active.getId(), workerId);
                }
            } catch (Exception e) {
                log.warn("Failed to touch heartbeat: taskId={}, workerId={}", active.getId(), workerId, e);
            }
        }
    }

    @Scheduled(
            initialDelayString = "${app.document-index.worker-initial-delay-ms:5000}",
            fixedDelayString = "${app.document-index.worker-poll-fixed-delay-ms:5000}"
    )
    public void pollAndProcess() {
        triggerAsync();
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdown();
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
            if (!heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                heartbeatScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            heartbeatScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
