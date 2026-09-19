package com.kama.jmindops.service;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DocumentIndexTaskVersionAllocationTest {

    @Mock
    private DocumentIndexTaskStore taskStore;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentStorageService storageService;

    @Mock
    private DocumentIndexTaskWorker worker;

    @InjectMocks
    private DocumentIndexTaskEnqueueService enqueueService;

    @BeforeEach
    void setUp() {
        // Enqueue service may require lenient mocks if not all methods are called.
        lenient().doNothing().when(taskStore).lockDocument(anyString(), anyString());
        lenient().doNothing().when(taskStore).createTask(any());
    }

    @Test
    void failedVersionDoesNotReuseVersionNumber() {
        Document existing = new Document();
        existing.setId("doc-A");
        existing.setIndexVersion(1);
        existing.setIndexStatus("READY");

        DocumentIndexTask latestTask = new DocumentIndexTask();
        latestTask.setDocumentId("doc-A");
        latestTask.setIndexVersion(2);
        latestTask.setStatus(DocumentIndexTaskStatus.FAILED);

        when(documentMapper.selectByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(existing);
        when(taskStore.findLatestByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.of(latestTask));
        when(taskStore.findLatestActiveByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.empty());

        var result = enqueueService.enqueue("kb-1", "test.txt", "text/plain", 100, "path/to/test.txt", "newhash", "src-1", "newfp", 3, null);

        assertEquals("doc-A", result.documentId());
        assertEquals(3, result.version());
        assertNotNull(result.task());
        assertFalse(result.task().isNewDocument());
    }

    @Test
    void cancelledVersionConsumesVersionNumber() {
        Document existing = new Document();
        existing.setId("doc-A");
        existing.setIndexVersion(1);
        existing.setIndexStatus("READY");

        DocumentIndexTask latestTask = new DocumentIndexTask();
        latestTask.setDocumentId("doc-A");
        latestTask.setIndexVersion(2);
        latestTask.setStatus(DocumentIndexTaskStatus.CANCELLED);

        when(documentMapper.selectByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(existing);
        when(taskStore.findLatestByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.of(latestTask));
        when(taskStore.findLatestActiveByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.empty());

        var result = enqueueService.enqueue("kb-1", "test.txt", "text/plain", 100, "path/to/test.txt", "newhash", "src-1", "newfp", 3, null);

        assertEquals("doc-A", result.documentId());
        assertEquals(3, result.version());
        assertNotNull(result.task());
        assertFalse(result.task().isNewDocument());
    }

    @Test
    void firstVersionFailurePreservesDocumentIdentity() {
        DocumentIndexTask latestTask = new DocumentIndexTask();
        latestTask.setDocumentId("doc-A");
        latestTask.setIndexVersion(1);
        latestTask.setStatus(DocumentIndexTaskStatus.FAILED);

        when(documentMapper.selectByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(null);
        when(taskStore.findLatestByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.of(latestTask));
        when(taskStore.findLatestActiveByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.empty());

        var result = enqueueService.enqueue("kb-1", "test.txt", "text/plain", 100, "path/to/test.txt", "newhash", "src-1", "newfp", 3, null);

        assertEquals("doc-A", result.documentId());
        assertEquals(2, result.version());
        assertNotNull(result.task());
        assertTrue(result.task().isNewDocument());
    }

    @Test
    void failedSameContentStillCreatesNewTask() {
        DocumentIndexTask latestTask = new DocumentIndexTask();
        latestTask.setDocumentId("doc-A");
        latestTask.setIndexVersion(2);
        latestTask.setStatus(DocumentIndexTaskStatus.FAILED);
        latestTask.setContentHash("abc");

        when(documentMapper.selectByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(null);
        when(taskStore.findLatestByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.of(latestTask));
        when(taskStore.findLatestActiveByKbIdAndSourceKey("kb-1", "src-1")).thenReturn(Optional.empty());

        var result = enqueueService.enqueue("kb-1", "test.txt", "text/plain", 100, "path/to/test.txt", "abc", "src-1", "fp", 3, null);

        // Cannot UNCHANGED, must create v3
        assertEquals("doc-A", result.documentId());
        assertEquals(3, result.version());
        assertNotEquals("UNCHANGED", result.action());
        assertNotNull(result.task());
        assertTrue(result.task().isNewDocument());
    }

    @Test
    void failedInitialVersionNextVersionCommitsAsNewDocument() throws Exception {
        String kbId = "kb-regression";
        String sourceKey = "guide.txt";
        String docId = "doc-reg-1";

        // 1. v1 FAILED, document row absent in document table
        when(documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(null);
        when(documentMapper.selectById(docId)).thenReturn(null);

        DocumentIndexTask v1Task = DocumentIndexTask.builder()
                .id("task-v1")
                .kbId(kbId)
                .documentId(docId)
                .indexVersion(1)
                .status(DocumentIndexTaskStatus.FAILED)
                .filePath("docs/v1.txt")
                .filename("guide.txt")
                .sourceKey(sourceKey)
                .build();

        when(taskStore.findLatestByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.of(v1Task));
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.empty());

        // 2. enqueue v2
        var result = enqueueService.enqueue(
                kbId, "guide.txt", "txt", 100L, "docs/v2.txt", "hash-v2", sourceKey, "fp-1", 3, null);

        // Verification: same documentId, version=2, isNewDocument=true
        assertEquals(docId, result.documentId());
        assertEquals(2, result.version());
        assertNotNull(result.task());
        assertTrue(result.task().isNewDocument());

        // 3. execution and final commit
        DocumentIndexTask v2Task = result.task();
        v2Task.setStatus(DocumentIndexTaskStatus.RUNNING);
        v2Task.setWorkerId("worker-test");
        v2Task.setLeaseVersion(1L);

        Path tempFile = Files.createTempFile("test-v2", ".txt");
        try {
            Files.writeString(tempFile, "guide content");
            when(storageService.getFilePath("docs/v2.txt")).thenReturn(tempFile);

            MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
            DocumentParserService documentParserService = mock(DocumentParserService.class);
            when(documentParserService.parse(any(), eq("txt"))).thenReturn(
                    DocumentParserService.ParsedDocument.builder()
                            .content("guide content")
                            .fileType("txt")
                            .build());

            IncrementalDocumentIndexService incrementalIndexService = mock(IncrementalDocumentIndexService.class);
            when(incrementalIndexService.prepareIndex(any(Document.class), eq(true), eq(0), any()))
                    .thenAnswer(invocation -> {
                        Document doc = invocation.getArgument(0);
                        doc.setIndexStatus("READY");
                        return new IncrementalDocumentIndexService.PreparedIndex(
                                doc, true, 0, List.of(),
                                new IncrementalDocumentIndexService.IndexResult(1, 0, 1));
                    });

            ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
            DocumentIndexStore indexStore = new DocumentIndexStore(documentMapper, chunkMapper);
            DocumentIndexCommitService commitService = new DocumentIndexCommitService(taskStore, indexStore);

            when(taskStore.findAndLockForCommit(v2Task.getId())).thenReturn(v2Task);
            when(taskStore.markSucceeded(eq(v2Task.getId()), eq("worker-test"), eq(1L), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(documentMapper.insert(any(Document.class))).thenReturn(1);

            DocumentIndexTaskExecutor executor = new DocumentIndexTaskExecutor(
                    storageService, markdownParserService, documentParserService,
                    incrementalIndexService, documentMapper, commitService, taskStore);

            executor.execute(v2Task);

            // Verification: final commit 使用 INSERT, document version=2 READY
            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentMapper).insert(docCaptor.capture());
            Document insertedDoc = docCaptor.getValue();
            assertEquals(docId, insertedDoc.getId());
            assertEquals(2, insertedDoc.getIndexVersion());
            assertEquals("READY", insertedDoc.getIndexStatus());

            // Verification: UPDATE is NOT called
            verify(documentMapper, never()).updateIndexByVersion(any(), anyInt());

            // Verification: task v2 SUCCEEDED
            assertEquals(DocumentIndexTaskStatus.SUCCEEDED, v2Task.getStatus());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void failedAndCancelledIntermediateVersionsUpdateWithActualDbVersion() throws Exception {
        String kbId = "kb-regression-2";
        String sourceKey = "guide2.txt";
        String docId = "doc-reg-2";

        // 1. document v1 READY in DB
        Document existingInDb = Document.builder()
                .id(docId)
                .kbId(kbId)
                .filename("guide2.txt")
                .sourceKey(sourceKey)
                .indexVersion(1)
                .indexStatus("READY")
                .contentHash("hash-v1")
                .indexFingerprint("fp-1")
                .chunkCount(1)
                .build();
        when(documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(existingInDb);
        when(documentMapper.selectById(docId)).thenReturn(existingInDb);

        // v3 CANCELLED (v2 FAILED, v3 CANCELLED in task history)
        DocumentIndexTask v3Task = DocumentIndexTask.builder()
                .id("task-v3")
                .kbId(kbId)
                .documentId(docId)
                .indexVersion(3)
                .status(DocumentIndexTaskStatus.CANCELLED)
                .filePath("docs/v3.txt")
                .filename("guide2.txt")
                .sourceKey(sourceKey)
                .build();

        when(taskStore.findLatestByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.of(v3Task));
        when(taskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey)).thenReturn(Optional.empty());

        // 2. enqueue v4
        var result = enqueueService.enqueue(
                kbId, "guide2.txt", "txt", 120L, "docs/v4.txt", "hash-v4", sourceKey, "fp-1", 3, null);

        // Verification: same documentId, version=4, isNewDocument=false
        assertEquals(docId, result.documentId());
        assertEquals(4, result.version());
        assertNotNull(result.task());
        assertFalse(result.task().isNewDocument());

        // 3. execution and final commit
        DocumentIndexTask v4Task = result.task();
        v4Task.setStatus(DocumentIndexTaskStatus.RUNNING);
        v4Task.setWorkerId("worker-test");
        v4Task.setLeaseVersion(2L);

        Path tempFile = Files.createTempFile("test-v4", ".txt");
        try {
            Files.writeString(tempFile, "guide content v4");
            when(storageService.getFilePath("docs/v4.txt")).thenReturn(tempFile);

            MarkdownParserService markdownParserService = mock(MarkdownParserService.class);
            DocumentParserService documentParserService = mock(DocumentParserService.class);
            when(documentParserService.parse(any(), eq("txt"))).thenReturn(
                    DocumentParserService.ParsedDocument.builder()
                            .content("guide content v4")
                            .fileType("txt")
                            .build());

            IncrementalDocumentIndexService incrementalIndexService = mock(IncrementalDocumentIndexService.class);
            when(incrementalIndexService.prepareIndex(any(Document.class), eq(false), eq(1), any()))
                    .thenAnswer(invocation -> {
                        Document doc = invocation.getArgument(0);
                        doc.setIndexStatus("READY");
                        return new IncrementalDocumentIndexService.PreparedIndex(
                                doc, false, 1, List.of(),
                                new IncrementalDocumentIndexService.IndexResult(1, 0, 1));
                    });

            ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
            DocumentIndexStore indexStore = new DocumentIndexStore(documentMapper, chunkMapper);
            DocumentIndexCommitService commitService = new DocumentIndexCommitService(taskStore, indexStore);

            when(taskStore.findAndLockForCommit(v4Task.getId())).thenReturn(v4Task);
            when(taskStore.markSucceeded(eq(v4Task.getId()), eq("worker-test"), eq(2L), anyInt(), anyInt(), anyInt()))
                    .thenReturn(true);
            when(documentMapper.updateIndexByVersion(any(Document.class), eq(1))).thenReturn(1);

            DocumentIndexTaskExecutor executor = new DocumentIndexTaskExecutor(
                    storageService, markdownParserService, documentParserService,
                    incrementalIndexService, documentMapper, commitService, taskStore);

            executor.execute(v4Task);

            // Verification: final commit uses UPDATE with CAS expectedVersion=1 (NOT 3)
            ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
            verify(documentMapper).updateIndexByVersion(docCaptor.capture(), eq(1));
            Document updatedDoc = docCaptor.getValue();
            assertEquals(docId, updatedDoc.getId());
            assertEquals(4, updatedDoc.getIndexVersion());
            assertEquals("READY", updatedDoc.getIndexStatus());

            // Verification: INSERT is NOT called
            verify(documentMapper, never()).insert(any());

            // Verification: task v4 SUCCEEDED
            assertEquals(DocumentIndexTaskStatus.SUCCEEDED, v4Task.getStatus());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
