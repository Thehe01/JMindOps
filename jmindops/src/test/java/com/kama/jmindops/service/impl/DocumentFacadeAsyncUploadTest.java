package com.kama.jmindops.service.impl;

import com.kama.jmindops.converter.DocumentConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import com.kama.jmindops.model.response.CreateDocumentResponse;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.DocumentHashing;
import com.kama.jmindops.service.DocumentIndexTaskStore;
import com.kama.jmindops.service.DocumentIndexTaskWorker;
import com.kama.jmindops.service.DocumentParserService;
import com.kama.jmindops.service.DocumentStorageService;
import com.kama.jmindops.service.IncrementalDocumentIndexService;
import com.kama.jmindops.service.IndexRetryPolicy;
import com.kama.jmindops.service.MarkdownParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentFacadeAsyncUploadTest {

    private final String kbId = "11111111-1111-1111-1111-111111111111";
    private final String documentId = "22222222-2222-2222-2222-222222222222";
    private final String indexFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private DocumentMapper documentMapper;
    private DocumentConverter converter;
    private DocumentStorageService storage;
    private MarkdownParserService markdown;
    private DocumentParserService parser;
    private IncrementalDocumentIndexService indexService;
    private ResourceAccessService access;
    private DocumentIndexTaskStore taskStore;
    private DocumentIndexTaskWorker taskWorker;
    private IndexRetryPolicy retryPolicy;
    private DocumentFacadeServiceImpl service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        converter = mock(DocumentConverter.class);
        storage = mock(DocumentStorageService.class);
        markdown = mock(MarkdownParserService.class);
        parser = mock(DocumentParserService.class);
        indexService = mock(IncrementalDocumentIndexService.class);
        access = mock(ResourceAccessService.class);
        taskStore = mock(DocumentIndexTaskStore.class);
        taskWorker = mock(DocumentIndexTaskWorker.class);
        retryPolicy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        when(indexService.currentFingerprint()).thenReturn(indexFingerprint);

        service = new DocumentFacadeServiceImpl(
                documentMapper, converter, storage, markdown, parser, indexService, access,
                taskStore, taskWorker, retryPolicy);
    }

    @Test
    void httpUploadDoesNotWaitForEmbeddingAndCreatesTask() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.md", "text/markdown", "Hello world markdown content".getBytes());
        when(documentMapper.selectByKbIdAndSourceKey(kbId, "guide.md")).thenReturn(null);
        when(storage.saveFile(eq(kbId), any(), eq(file))).thenReturn("stored/guide.md");

        CreateDocumentResponse response = service.uploadDocument(kbId, file);

        assertThat(response.getDocumentId()).isNotBlank();
        assertThat(response.getIndexAction()).isEqualTo("CREATED");
        assertThat(response.getIndexVersion()).isEqualTo(1);
        assertThat(response.getChunkCount()).isZero();
        assertThat(response.getReusedChunkCount()).isZero();
        assertThat(response.getEmbeddedChunkCount()).isZero();

        // Verify incrementalIndexService.replaceIndex was NEVER called during HTTP upload
        verify(indexService, never()).replaceIndex(any(), anyBoolean(), any());

        // Verify task was created in store with PENDING status
        ArgumentCaptor<DocumentIndexTask> taskCaptor = ArgumentCaptor.forClass(DocumentIndexTask.class);
        verify(taskStore).createTask(taskCaptor.capture());
        DocumentIndexTask createdTask = taskCaptor.getValue();
        assertThat(createdTask.getStatus()).isEqualTo(DocumentIndexTaskStatus.PENDING);
        assertThat(createdTask.getDocumentId()).isEqualTo(response.getDocumentId());
        assertThat(createdTask.getIndexVersion()).isEqualTo(1);
        assertThat(createdTask.getFilePath()).isEqualTo("stored/guide.md");
        assertThat(createdTask.getFilename()).isEqualTo("guide.md");
        assertThat(createdTask.getContentHash()).isEqualTo(DocumentHashing.sha256("Hello world markdown content"));
        assertThat(createdTask.isNewDocument()).isTrue();

        // Verify worker was triggered asynchronously
        verify(taskWorker).triggerAsync();
    }

    @Test
    void unchangedFileReturnsImmediatelyWithoutCreatingTask() throws Exception {
        byte[] bytes = "content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", bytes);
        Document existing = Document.builder()
                .id(documentId)
                .kbId(kbId)
                .filename("notes.txt")
                .sourceKey("notes.txt")
                .contentHash(DocumentHashing.sha256("content"))
                .indexFingerprint(indexFingerprint)
                .indexVersion(2)
                .indexStatus("READY")
                .chunkCount(5)
                .build();
        when(documentMapper.selectByKbIdAndSourceKey(kbId, "notes.txt")).thenReturn(existing);

        CreateDocumentResponse response = service.uploadDocument(kbId, file);

        assertThat(response.getDocumentId()).isEqualTo(documentId);
        assertThat(response.getIndexAction()).isEqualTo("UNCHANGED");
        assertThat(response.getReusedChunkCount()).isEqualTo(5);
        assertThat(response.getEmbeddedChunkCount()).isZero();

        verify(taskStore, never()).createTask(any());
        verify(taskWorker, never()).triggerAsync();
        verify(storage, never()).saveFile(any(), any(), any());
    }

    @Test
    void duplicateTaskRejectedAndCleansUpFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.txt", "text/plain", "new version".getBytes());
        Document existing = Document.builder()
                .id(documentId)
                .kbId(kbId)
                .filename("dup.txt")
                .sourceKey("dup.txt")
                .contentHash("old-hash")
                .indexFingerprint(indexFingerprint)
                .indexVersion(1)
                .indexStatus("READY")
                .chunkCount(2)
                .build();
        when(documentMapper.selectByKbIdAndSourceKey(kbId, "dup.txt")).thenReturn(existing);
        when(storage.saveFile(eq(kbId), eq(documentId), eq(file))).thenReturn("stored/dup.txt");

        doThrow(new BizException("重复创建文档索引任务: documentId=" + documentId + ", version=2"))
                .when(taskStore).createTask(any());

        assertThatThrownBy(() -> service.uploadDocument(kbId, file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重复创建文档索引任务");

        verify(storage).deleteFile("stored/dup.txt");
    }

    @Test
    void inFlightUploadWithSameContentReturnsImmediatelyWithoutRedundantTask() throws Exception {
        byte[] bytes = "in-flight content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "inflight.txt", "text/plain", bytes);
        when(documentMapper.selectByKbIdAndSourceKey(kbId, "inflight.txt")).thenReturn(null);

        DocumentIndexTask inFlightTask = DocumentIndexTask.builder()
                .id("task-inflight")
                .kbId(kbId)
                .documentId("doc-inflight")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.RUNNING)
                .contentHash(DocumentHashing.sha256("in-flight content"))
                .indexFingerprint(indexFingerprint)
                .build();
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, "inflight.txt"))
                .thenReturn(java.util.Optional.of(inFlightTask));

        CreateDocumentResponse response = service.uploadDocument(kbId, file);

        assertThat(response.getDocumentId()).isEqualTo("doc-inflight");
        assertThat(response.getIndexAction()).isEqualTo("UNCHANGED");
        assertThat(response.getIndexVersion()).isEqualTo(1);
        verify(taskStore, never()).createTask(any());
        verify(storage, never()).saveFile(any(), any(), any());
    }

    @Test
    void inFlightUploadWithModifiedContentReusesDocumentIdAndBumpsVersion() throws Exception {
        byte[] bytes = "new modified content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "inflight.txt", "text/plain", bytes);
        when(documentMapper.selectByKbIdAndSourceKey(kbId, "inflight.txt")).thenReturn(null);

        DocumentIndexTask inFlightTask = DocumentIndexTask.builder()
                .id("task-inflight-v1")
                .kbId(kbId)
                .documentId("doc-inflight")
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.RUNNING)
                .contentHash(DocumentHashing.sha256("original in-flight content"))
                .indexFingerprint(indexFingerprint)
                .build();
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, "inflight.txt"))
                .thenReturn(java.util.Optional.of(inFlightTask));
        when(storage.saveFile(eq(kbId), eq("doc-inflight"), eq(file))).thenReturn("stored/inflight-v2.txt");

        CreateDocumentResponse response = service.uploadDocument(kbId, file);

        assertThat(response.getDocumentId()).isEqualTo("doc-inflight");
        assertThat(response.getIndexAction()).isEqualTo("UPDATED");
        assertThat(response.getIndexVersion()).isEqualTo(2);

        ArgumentCaptor<DocumentIndexTask> captor = ArgumentCaptor.forClass(DocumentIndexTask.class);
        verify(taskStore).createTask(captor.capture());
        DocumentIndexTask task = captor.getValue();
        assertThat(task.getDocumentId()).isEqualTo("doc-inflight");
        assertThat(task.getIndexVersion()).isEqualTo(2);
        assertThat(task.isNewDocument()).isFalse();
    }
}
