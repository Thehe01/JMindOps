package com.kama.jmindops.service;

import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskOldFileCleanupTest {

    private DocumentStorageService storageService;
    private MarkdownParserService markdownParserService;
    private DocumentParserService documentParserService;
    private IncrementalDocumentIndexService incrementalService;
    private DocumentMapper documentMapper;
    private DocumentIndexCommitService commitService;
    private DocumentIndexTaskStore taskStore;
    private DocumentIndexTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        storageService = mock(DocumentStorageService.class);
        markdownParserService = mock(MarkdownParserService.class);
        documentParserService = mock(DocumentParserService.class);
        incrementalService = mock(IncrementalDocumentIndexService.class);
        documentMapper = mock(DocumentMapper.class);
        commitService = mock(DocumentIndexCommitService.class);
        taskStore = mock(DocumentIndexTaskStore.class);

        executor = new DocumentIndexTaskExecutor(
                storageService,
                markdownParserService,
                documentParserService,
                incrementalService,
                documentMapper,
                commitService,
                taskStore
        );

        Path tempFile = java.nio.file.Files.createTempFile("test-old-cleanup", ".md");
        java.nio.file.Files.writeString(tempFile, "# Section\nContent");
        tempFile.toFile().deleteOnExit();
        when(storageService.getFilePath(anyString())).thenReturn(tempFile);
    }

    @Test
    void cleanupOldFileDeletesOldPhysicalFileWhenNotInUse() throws Exception {
        String oldPath = "docs/old-v1.md";
        String newPath = "docs/new-v2.md";

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-cleanup-1")
                .documentId("doc-1")
                .kbId("kb-1")
                .indexVersion(2)
                .workerId("worker-cleanup")
                .status(DocumentIndexTaskStatus.RUNNING)
                .leaseVersion(1L)
                .filePath(newPath)
                .oldFilePath(oldPath)
                .filetype("md")
                .isNewDocument(false)
                .build();

        when(documentMapper.selectById("doc-1")).thenReturn(null);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("Title", "Content")
        ));

        IncrementalDocumentIndexService.IndexResult indexResult = new IncrementalDocumentIndexService.IndexResult(1, 0, 1);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                Document.builder().id("doc-1").kbId("kb-1").indexVersion(2).build(),
                false, List.of(), indexResult);
        when(incrementalService.prepareIndex(any(), anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(prepared);
        when(taskStore.isFilePathInUse(oldPath, "task-cleanup-1")).thenReturn(false);

        DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

        assertThat(result).isNotNull();
        assertThat(result.chunkCount()).isEqualTo(1);
        verify(taskStore).isFilePathInUse(oldPath, "task-cleanup-1");
        verify(storageService).deleteFile(oldPath);
    }

    @Test
    void cleanupOldFileSkipsDeletionWhenOldFileStillInUse() throws Exception {
        String oldPath = "docs/shared-v1.md";
        String newPath = "docs/new-v2.md";

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-cleanup-2")
                .documentId("doc-2")
                .kbId("kb-1")
                .indexVersion(2)
                .workerId("worker-cleanup")
                .status(DocumentIndexTaskStatus.RUNNING)
                .leaseVersion(1L)
                .filePath(newPath)
                .oldFilePath(oldPath)
                .filetype("md")
                .isNewDocument(false)
                .build();

        when(documentMapper.selectById("doc-2")).thenReturn(null);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("Title", "Content")
        ));

        IncrementalDocumentIndexService.IndexResult indexResult = new IncrementalDocumentIndexService.IndexResult(1, 0, 1);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                Document.builder().id("doc-2").kbId("kb-1").indexVersion(2).build(),
                false, List.of(), indexResult);
        when(incrementalService.prepareIndex(any(), anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(prepared);
        // File is in use by another document or active task
        when(taskStore.isFilePathInUse(oldPath, "task-cleanup-2")).thenReturn(true);

        DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

        assertThat(result).isNotNull();
        verify(taskStore).isFilePathInUse(oldPath, "task-cleanup-2");
        verify(storageService, never()).deleteFile(oldPath);
    }

    @Test
    void cleanupOldFileFailureDoesNotFailIndexTask() throws Exception {
        String oldPath = "docs/locked-v1.md";
        String newPath = "docs/new-v2.md";

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-cleanup-3")
                .documentId("doc-3")
                .kbId("kb-1")
                .indexVersion(2)
                .workerId("worker-cleanup")
                .status(DocumentIndexTaskStatus.RUNNING)
                .leaseVersion(1L)
                .filePath(newPath)
                .oldFilePath(oldPath)
                .filetype("md")
                .isNewDocument(false)
                .build();

        when(documentMapper.selectById("doc-3")).thenReturn(null);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("Title", "Content")
        ));

        IncrementalDocumentIndexService.IndexResult indexResult = new IncrementalDocumentIndexService.IndexResult(1, 0, 1);
        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                Document.builder().id("doc-3").kbId("kb-1").indexVersion(2).build(),
                false, List.of(), indexResult);
        when(incrementalService.prepareIndex(any(), anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(prepared);
        when(taskStore.isFilePathInUse(oldPath, "task-cleanup-3")).thenReturn(false);

        // Simulate OS file deletion lock/error
        doThrow(new RuntimeException("Permission denied")).when(storageService).deleteFile(oldPath);

        // Should complete without throwing
        DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

        assertThat(result).isNotNull();
        verify(storageService).deleteFile(oldPath);
    }
}
