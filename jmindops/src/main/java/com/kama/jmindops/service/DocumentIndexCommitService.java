package com.kama.jmindops.service;

import com.kama.jmindops.exception.StaleDocumentIndexLeaseException;
import com.kama.jmindops.exception.TaskCancelledException;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 负责索引原子提交与 Fencing 校验边界：
 * 验证当前 Worker 租约有效性、执行文档与 Chunk 的原子替换、并将任务标记为 SUCCEEDED。
 * 如果租约已失效或任务已被取消，事务回滚，禁止修改任何 document/chunk。
 */
@Service
public class DocumentIndexCommitService {
    private final DocumentIndexTaskStore taskStore;
    private final DocumentIndexStore indexStore;

    public DocumentIndexCommitService(DocumentIndexTaskStore taskStore, DocumentIndexStore indexStore) {
        this.taskStore = taskStore;
        this.indexStore = indexStore;
    }

    @Transactional
    public IncrementalDocumentIndexService.IndexResult commit(
            DocumentIndexTask task,
            String workerId,
            long leaseVersion,
            IncrementalDocumentIndexService.PreparedIndex prepared
    ) {
        commit(task, workerId, leaseVersion, prepared.document(), prepared.newDocument(), prepared.expectedVersion(), prepared.chunks(), prepared.result());
        return prepared.result();
    }

    @Transactional
    public void commit(
            DocumentIndexTask task,
            String workerId,
            long leaseVersion,
            Document document,
            boolean newDocument,
            List<ChunkBgeM3> chunks,
            IncrementalDocumentIndexService.IndexResult result
    ) {
        int expectedVersion = document != null && document.getIndexVersion() != null ? document.getIndexVersion() - 1 : 0;
        commit(task, workerId, leaseVersion, document, newDocument, expectedVersion, chunks, result);
    }

    @Transactional
    public void commit(
            DocumentIndexTask task,
            String workerId,
            long leaseVersion,
            Document document,
            boolean newDocument,
            int expectedVersion,
            List<ChunkBgeM3> chunks,
            IncrementalDocumentIndexService.IndexResult result
    ) {
        // 1. 对 document_index_task 做 ownership 与 lease_version fencing 校验（加行锁）
        DocumentIndexTask current = taskStore.findAndLockForCommit(task.getId());
        if (current == null) {
            throw new StaleDocumentIndexLeaseException("Task not found for commit: taskId=" + task.getId());
        }
        if (Boolean.TRUE.equals(current.getCancelRequested())) {
            throw new TaskCancelledException("Task has been cancelled: taskId=" + task.getId());
        }
        if (current.getStatus() != DocumentIndexTaskStatus.RUNNING
                || !workerId.equals(current.getWorkerId())
                || current.getLeaseVersion() == null
                || current.getLeaseVersion() != leaseVersion) {
            throw new StaleDocumentIndexLeaseException(String.format(
                    "Stale lease on commit: taskId=%s, currentStatus=%s, currentWorker=%s, currentLease=%s, expectedWorker=%s, expectedLease=%d",
                    task.getId(), current.getStatus(), current.getWorkerId(), current.getLeaseVersion(), workerId, leaseVersion));
        }

        // 2. 执行原子 index replace（基于 optimistic expectedVersion）
        indexStore.replace(document, newDocument, expectedVersion, chunks);

        // 3. 将 document_index_task 更新为 SUCCEEDED（绑定 worker_id 与 lease_version）
        boolean marked = taskStore.markSucceeded(
                task.getId(), workerId, leaseVersion,
                result.chunkCount(), result.reusedChunkCount(), result.embeddedChunkCount());
        if (!marked) {
            throw new StaleDocumentIndexLeaseException(
                    "Failed to mark task SUCCEEDED (lease expired during commit): taskId=" + task.getId());
        }
        task.setStatus(DocumentIndexTaskStatus.SUCCEEDED);
    }
}
