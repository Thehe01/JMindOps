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
}
