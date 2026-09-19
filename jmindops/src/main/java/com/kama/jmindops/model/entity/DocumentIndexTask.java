package com.kama.jmindops.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PostgreSQL 持久化文档索引任务实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexTask {
    private String id;
    private String kbId;
    private String documentId;
    private Integer indexVersion;
    private DocumentIndexTaskStatus status;
    private Long leaseVersion;
    private Boolean cancelRequested;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime nextRetryAt;
    private LocalDateTime heartbeatAt;
    private String workerId;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private String filePath;
    private String filename;
    private String filetype;
    private Long fileSize;
    private String contentHash;
    private String sourceKey;
    private String indexFingerprint;
    private boolean isNewDocument;
    private String oldFilePath;

    private Integer chunkCount;
    private Integer reusedChunkCount;
    private Integer embeddedChunkCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
