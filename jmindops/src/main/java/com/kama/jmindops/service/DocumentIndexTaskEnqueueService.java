package com.kama.jmindops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.dto.DocumentDTO;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责在短数据库事务中执行并发受控的文档索引任务入队：
 * 1. 获取 PostgreSQL advisory lock (kbId, sourceKey)
 * 2. 重新加载最新 document 与 active task（防止 TOCTOU 竞态）
 * 3. 分配正确的 documentId、版本号及旧文件路径
 * 4. 插入 document_index_task (PENDING)
 * 5. 注册 TransactionSynchronization：事务提交后触发 Worker；事务回滚时清理新上传文件
 */
@Service
public class DocumentIndexTaskEnqueueService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexTaskEnqueueService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentIndexTaskStore taskStore;
    private final DocumentMapper documentMapper;
    private final DocumentStorageService storageService;
    private final DocumentIndexTaskWorker worker;

    public DocumentIndexTaskEnqueueService(
            DocumentIndexTaskStore taskStore,
            DocumentMapper documentMapper,
            DocumentStorageService storageService
    ) {
        this(taskStore, documentMapper, storageService, null);
    }

    @Autowired
    public DocumentIndexTaskEnqueueService(
            DocumentIndexTaskStore taskStore,
            DocumentMapper documentMapper,
            DocumentStorageService storageService,
            @Autowired(required = false) DocumentIndexTaskWorker worker
    ) {
        this.taskStore = taskStore;
        this.documentMapper = documentMapper;
        this.storageService = storageService;
        this.worker = worker;
    }

    public record EnqueueResult(
            String documentId,
            int version,
            String action,
            DocumentIndexTask task
    ) {}

    @Transactional
    public EnqueueResult enqueue(
            String kbId,
            String originalFilename,
            String filetype,
            long fileSize,
            String newFilePath,
            String contentHash,
            String sourceKey,
            String indexFingerprint,
            int maxRetries,
            String preferredDocumentId
    ) {
        // 1. 事务内获取 PostgreSQL 事务级咨询锁（锁定该知识库下的源文件标识）
        taskStore.lockDocument(kbId, sourceKey);

        // 2. 加锁后重新查询 document 表
        Document existing = documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey);
        
        Optional<DocumentIndexTask> latestTaskOpt = taskStore.findLatestByKbIdAndSourceKey(kbId, sourceKey);
        Optional<DocumentIndexTask> activeTaskOpt = taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey);

        if (existing != null
                && contentHash.equals(existing.getContentHash())
                && indexFingerprint.equals(existing.getIndexFingerprint())
                && "READY".equals(existing.getIndexStatus())) {
            log.info("文档内容未变化，无需创建新索引任务: kbId={}, documentId={}", kbId, existing.getId());
            cleanupFile(newFilePath);
            return new EnqueueResult(
                    existing.getId(),
                    existing.getIndexVersion() != null ? existing.getIndexVersion() : 1,
                    "UNCHANGED",
                    null
            );
        }

        // 3. 活跃任务去重
        if (activeTaskOpt.isPresent()) {
            DocumentIndexTask activeTask = activeTaskOpt.get();
            if (contentHash.equals(activeTask.getContentHash())
                    && indexFingerprint.equals(activeTask.getIndexFingerprint())) {
                log.info("文档存在进行中的相同索引任务，跳过重复创建: kbId={}, documentId={}, taskId={}",
                        kbId, activeTask.getDocumentId(), activeTask.getId());
                cleanupFile(newFilePath);
                return new EnqueueResult(
                        activeTask.getDocumentId(),
                        activeTask.getIndexVersion(),
                        "UNCHANGED",
                        activeTask
                );
            }
        }

        // 4. 计算 documentId、nextVersion、oldFilePath
        boolean newDocument = existing == null;
        String documentId;

        if (existing != null) {
            documentId = existing.getId();
        } else if (latestTaskOpt.isPresent()) {
            documentId = latestTaskOpt.get().getDocumentId();
        } else if (preferredDocumentId != null && !preferredDocumentId.isBlank()) {
            documentId = preferredDocumentId;
        } else {
            documentId = UUID.randomUUID().toString();
        }

        int documentVersion = existing != null && existing.getIndexVersion() != null ? existing.getIndexVersion() : 0;
        int latestTaskVersion = latestTaskOpt.map(DocumentIndexTask::getIndexVersion).orElse(0);
        int nextVersion = Math.max(documentVersion, latestTaskVersion) + 1;

        String oldFilePath;
        if (existing != null) {
            oldFilePath = storedFilePath(existing);
        } else if (latestTaskOpt.isPresent()) {
            oldFilePath = latestTaskOpt.get().getFilePath();
        } else {
            oldFilePath = null;
        }

        LocalDateTime now = LocalDateTime.now();
        DocumentIndexTask task = DocumentIndexTask.builder()
                .id(UUID.randomUUID().toString())
                .kbId(kbId)
                .documentId(documentId)
                .indexVersion(nextVersion)
                .status(DocumentIndexTaskStatus.PENDING)
                .leaseVersion(0L)
                .cancelRequested(false)
                .retryCount(0)
                .maxRetries(maxRetries)
                .filePath(newFilePath)
                .filename(originalFilename)
                .filetype(filetype)
                .fileSize(fileSize)
                .contentHash(contentHash)
                .sourceKey(sourceKey)
                .indexFingerprint(indexFingerprint)
                .isNewDocument(newDocument)
                .oldFilePath(oldFilePath)
                .createdAt(now)
                .updatedAt(now)
                .build();

        taskStore.createTask(task);

        // 5. 确保 Worker 仅在事务提交后触发；若事务回滚则清理新物理文件
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (worker != null) {
                        worker.triggerAsync();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        cleanupFile(newFilePath);
                    }
                }
            });
        } else {
            if (worker != null) {
                worker.triggerAsync();
            }
        }

        log.info("文档持久异步索引任务已创建: kbId={}, documentId={}, taskId={}, version={}",
                kbId, documentId, task.getId(), nextVersion);

        return new EnqueueResult(
                documentId,
                nextVersion,
                newDocument ? "CREATED" : "UPDATED",
                task
        );
    }

    private String storedFilePath(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(document.getMetadata(), DocumentDTO.MetaData.class).getFilePath();
        } catch (Exception exception) {
            log.warn("无法解析旧文档文件路径: documentId={}", document.getId());
            return null;
        }
    }

    private void cleanupFile(String filePath) {
        if (filePath != null) {
            try {
                storageService.deleteFile(filePath);
            } catch (Exception e) {
                log.warn("清理文件失败: filePath={}, error={}", filePath, e.getMessage());
            }
        }
    }
}
