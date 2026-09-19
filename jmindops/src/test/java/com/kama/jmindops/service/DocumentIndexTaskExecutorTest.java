package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexTaskExecutorTest {

    private DocumentStorageService documentStorageService;
    private MarkdownParserService markdownParserService;
    private DocumentParserService documentParserService;
    private IncrementalDocumentIndexService incrementalIndexService;
    private DocumentIndexTaskExecutor executor;

    @BeforeEach
    void setUp() {
        documentStorageService = mock(DocumentStorageService.class);
        markdownParserService = mock(MarkdownParserService.class);
        documentParserService = mock(DocumentParserService.class);
        incrementalIndexService = mock(IncrementalDocumentIndexService.class);
        executor = new DocumentIndexTaskExecutor(
                documentStorageService, markdownParserService, documentParserService, incrementalIndexService);
    }

    @Test
    void executesIncrementalIndexAndCleansUpOldFile() throws Exception {
        Path tempFile = Files.createTempFile("test-doc", ".md");
        Files.writeString(tempFile, "# Section\nContent");
        when(documentStorageService.getFilePath("docs/test.md")).thenReturn(tempFile);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("Section", "Content")
        ));

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-1")
                .kbId("kb-1")
                .documentId("doc-1")
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.RUNNING)
                .filePath("docs/test.md")
                .filename("test.md")
                .filetype("md")
                .fileSize(100L)
                .contentHash("hash-new")
                .sourceKey("test.md")
                .indexFingerprint("fp-1")
                .isNewDocument(false)
                .oldFilePath("docs/old.md")
                .createdAt(LocalDateTime.now())
                .build();

        when(incrementalIndexService.replaceIndex(any(Document.class), eq(false), any()))
                .thenReturn(new IncrementalDocumentIndexService.IndexResult(1, 1, 0));

        DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(result.reusedChunkCount()).isEqualTo(1);
        assertThat(result.embeddedChunkCount()).isZero();

        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(incrementalIndexService).replaceIndex(docCaptor.capture(), eq(false), any());
        assertThat(docCaptor.getValue().getId()).isEqualTo("doc-1");
        assertThat(docCaptor.getValue().getIndexVersion()).isEqualTo(2);

        verify(documentStorageService).deleteFile("docs/old.md");

        Files.deleteIfExists(tempFile);
    }

    @Test
    void unchangedChunkReusesEmbeddingDuringExecution() throws Exception {
        Path tempFile = Files.createTempFile("test-reuse", ".md");
        Files.writeString(tempFile, "# A\nPart 1\n# B\nPart 2");
        when(documentStorageService.getFilePath("docs/reuse.md")).thenReturn(tempFile);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("A", "Part 1"),
                new MarkdownParserService.MarkdownSection("B", "Part 2")
        ));

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-2")
                .kbId("kb-1")
                .documentId("doc-2")
                .indexVersion(3)
                .status(DocumentIndexTaskStatus.RUNNING)
                .filePath("docs/reuse.md")
                .filename("reuse.md")
                .filetype("md")
                .fileSize(200L)
                .contentHash("hash-updated")
                .sourceKey("reuse.md")
                .indexFingerprint("fp-active")
                .isNewDocument(false)
                .oldFilePath(null)
                .createdAt(LocalDateTime.now())
                .build();

        when(incrementalIndexService.replaceIndex(any(Document.class), eq(false), any()))
                .thenReturn(new IncrementalDocumentIndexService.IndexResult(2, 1, 1));

        DocumentIndexTaskExecutor.ExecutionResult result = executor.execute(task);

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.reusedChunkCount()).isEqualTo(1);
        assertThat(result.embeddedChunkCount()).isEqualTo(1);

        verify(documentStorageService, never()).deleteFile(any());
        Files.deleteIfExists(tempFile);
    }

    @Test
    void throwsBizExceptionWhenDocumentHasNoIndexableContent() throws Exception {
        Path tempFile = Files.createTempFile("empty-doc", ".txt");
        when(documentStorageService.getFilePath("docs/empty.txt")).thenReturn(tempFile);
        when(documentParserService.parse(any(), eq("txt"))).thenReturn(
                DocumentParserService.ParsedDocument.builder()
                        .content("")
                        .fileType("txt")
                        .build()
        );

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-3")
                .kbId("kb-1")
                .documentId("doc-3")
                .indexVersion(1)
                .filePath("docs/empty.txt")
                .filename("empty.txt")
                .filetype("txt")
                .fileSize(0L)
                .contentHash("empty-hash")
                .sourceKey("empty.txt")
                .indexFingerprint("fp")
                .build();

        assertThatThrownBy(() -> executor.execute(task))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有可索引的文本内容");

        verify(incrementalIndexService, never()).replaceIndex(any(), anyBoolean(), any());
        Files.deleteIfExists(tempFile);
    }

    @Test
    void reexecutionIsIdempotentWhenDocumentAlreadyAtVersionAndReady() throws Exception {
        com.kama.jmindops.mapper.DocumentMapper documentMapper = mock(com.kama.jmindops.mapper.DocumentMapper.class);
        DocumentIndexTaskExecutor idempotentExecutor = new DocumentIndexTaskExecutor(
                documentStorageService, markdownParserService, documentParserService,
                incrementalIndexService, documentMapper);

        Document existingDoc = Document.builder()
                .id("doc-idempotent")
                .kbId("kb-1")
                .indexVersion(2)
                .indexStatus("READY")
                .contentHash("hash-ready")
                .indexFingerprint("fp-ready")
                .chunkCount(7)
                .build();

        when(documentMapper.selectById("doc-idempotent")).thenReturn(existingDoc);

        DocumentIndexTask task = DocumentIndexTask.builder()
                .id("task-idempotent")
                .kbId("kb-1")
                .documentId("doc-idempotent")
                .indexVersion(2)
                .status(DocumentIndexTaskStatus.RUNNING)
                .filePath("docs/new.md")
                .filename("new.md")
                .filetype("md")
                .fileSize(100L)
                .contentHash("hash-ready")
                .sourceKey("new.md")
                .indexFingerprint("fp-ready")
                .oldFilePath("docs/old.md")
                .build();

        DocumentIndexTaskExecutor.ExecutionResult result = idempotentExecutor.execute(task);

        assertThat(result.chunkCount()).isEqualTo(7);
        assertThat(result.reusedChunkCount()).isEqualTo(7);
        assertThat(result.embeddedChunkCount()).isZero();

        // Ensure replaceIndex was NOT called again
        verify(incrementalIndexService, never()).replaceIndex(any(), anyBoolean(), any());
        // Ensure old file cleanup still ran
        verify(documentStorageService).deleteFile("docs/old.md");
    }

    @Test
    void missingDocumentInDbInsertsDocumentAsNewEvenWithHigherTaskVersion() throws Exception {
        com.kama.jmindops.mapper.DocumentMapper documentMapper = mock(com.kama.jmindops.mapper.DocumentMapper.class);
        DocumentIndexCommitService commitService = mock(DocumentIndexCommitService.class);
        DocumentIndexTaskStore taskStore = mock(DocumentIndexTaskStore.class);

        DocumentIndexTaskExecutor executorWithDb = new DocumentIndexTaskExecutor(
                documentStorageService, markdownParserService, documentParserService,
                incrementalIndexService, documentMapper, commitService, taskStore);

        Path tempFile = Files.createTempFile("test-v3", ".md");
        Files.writeString(tempFile, "# V3 Content");
        when(documentStorageService.getFilePath("docs/v3.md")).thenReturn(tempFile);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("V3", "Content")
        ));

        // Document row absent in document table (e.g. v1 FAILED, v2 FAILED)
        when(documentMapper.selectById("doc-100")).thenReturn(null);

        // Task has version 3 and isNewDocument snapshot might even have been false previously
        DocumentIndexTask taskV3 = DocumentIndexTask.builder()
                .id("task-v3")
                .kbId("kb-1")
                .documentId("doc-100")
                .indexVersion(3)
                .status(DocumentIndexTaskStatus.RUNNING)
                .filePath("docs/v3.md")
                .filename("v3.md")
                .filetype("md")
                .fileSize(100L)
                .contentHash("hash-v3")
                .sourceKey("v3.md")
                .indexFingerprint("fp-1")
                .isNewDocument(false) // Even if task snapshot was false!
                .workerId("worker-1")
                .leaseVersion(2L)
                .build();

        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                Document.builder().id("doc-100").indexVersion(3).build(),
                true, 0, List.of(), new IncrementalDocumentIndexService.IndexResult(1, 0, 1));

        when(incrementalIndexService.prepareIndex(any(Document.class), eq(true), eq(0), any()))
                .thenReturn(prepared);

        DocumentIndexTaskExecutor.ExecutionResult result = executorWithDb.execute(taskV3);

        // Verified prepareIndex received isNew=true and expectedVersion=0
        verify(incrementalIndexService).prepareIndex(any(Document.class), eq(true), eq(0), any());
        verify(commitService).commit(eq(taskV3), eq("worker-1"), eq(2L), eq(prepared));
        assertThat(result.chunkCount()).isEqualTo(1);

        Files.deleteIfExists(tempFile);
    }

    @Test
    void existingDocumentInDbUsesActualDbVersionAsCasExpectedVersion() throws Exception {
        com.kama.jmindops.mapper.DocumentMapper documentMapper = mock(com.kama.jmindops.mapper.DocumentMapper.class);
        DocumentIndexCommitService commitService = mock(DocumentIndexCommitService.class);
        DocumentIndexTaskStore taskStore = mock(DocumentIndexTaskStore.class);

        DocumentIndexTaskExecutor executorWithDb = new DocumentIndexTaskExecutor(
                documentStorageService, markdownParserService, documentParserService,
                incrementalIndexService, documentMapper, commitService, taskStore);

        Path tempFile = Files.createTempFile("test-v4", ".md");
        Files.writeString(tempFile, "# V4 Content");
        when(documentStorageService.getFilePath("docs/v4.md")).thenReturn(tempFile);
        when(markdownParserService.parseMarkdown(any())).thenReturn(List.of(
                new MarkdownParserService.MarkdownSection("V4", "Content")
        ));

        // Document row in DB is at version 1 (e.g. v1 READY, v2 FAILED, v3 CANCELLED, v4 PENDING)
        Document existingInDb = Document.builder()
                .id("doc-200")
                .kbId("kb-1")
                .indexVersion(1)
                .indexStatus("READY")
                .contentHash("hash-v1")
                .indexFingerprint("fp-1")
                .chunkCount(2)
                .build();
        when(documentMapper.selectById("doc-200")).thenReturn(existingInDb);

        // Task has version 4
        DocumentIndexTask taskV4 = DocumentIndexTask.builder()
                .id("task-v4")
                .kbId("kb-1")
                .documentId("doc-200")
                .indexVersion(4)
                .status(DocumentIndexTaskStatus.RUNNING)
                .filePath("docs/v4.md")
                .filename("v4.md")
                .filetype("md")
                .fileSize(120L)
                .contentHash("hash-v4")
                .sourceKey("v4.md")
                .indexFingerprint("fp-1")
                .workerId("worker-1")
                .leaseVersion(3L)
                .build();

        IncrementalDocumentIndexService.PreparedIndex prepared = new IncrementalDocumentIndexService.PreparedIndex(
                Document.builder().id("doc-200").indexVersion(4).build(),
                false, 1, List.of(), new IncrementalDocumentIndexService.IndexResult(1, 0, 1));

        // Crucial: expectedVersion MUST be 1 (from DB), NOT 3 (4 - 1)!
        when(incrementalIndexService.prepareIndex(any(Document.class), eq(false), eq(1), any()))
                .thenReturn(prepared);

        DocumentIndexTaskExecutor.ExecutionResult result = executorWithDb.execute(taskV4);

        verify(incrementalIndexService).prepareIndex(any(Document.class), eq(false), eq(1), any());
        verify(commitService).commit(eq(taskV4), eq("worker-1"), eq(3L), eq(prepared));
        assertThat(result.chunkCount()).isEqualTo(1);

        Files.deleteIfExists(tempFile);
    }
}
