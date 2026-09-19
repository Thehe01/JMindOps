package com.kama.jmindops.service;

import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    }
}
