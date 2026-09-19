package com.kama.jmindops.service.impl;

import com.kama.jmindops.converter.DocumentConverter;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.response.CreateDocumentResponse;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.DocumentHashing;
import com.kama.jmindops.service.DocumentParserService;
import com.kama.jmindops.service.DocumentStorageService;
import com.kama.jmindops.service.IncrementalDocumentIndexService;
import com.kama.jmindops.service.MarkdownParserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentFacadeIncrementalUploadTest {
    @Test
    void skipsStorageParsingAndEmbeddingWhenFileHashIsUnchanged() throws Exception {
        Fixture fixture = new Fixture();
        byte[] bytes = "same content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "Notes.md", "text/markdown", bytes);
        Document existing = Document.builder()
                .id(fixture.documentId)
                .kbId(fixture.kbId)
                .filename("Notes.md")
                .sourceKey("notes.md")
                .contentHash(DocumentHashing.sha256("same content"))
                .indexFingerprint(fixture.indexFingerprint)
                .indexVersion(2)
                .indexStatus("READY")
                .chunkCount(4)
                .build();
        when(fixture.documentMapper.selectByKbIdAndSourceKey(fixture.kbId, "notes.md"))
                .thenReturn(existing);

        CreateDocumentResponse response = fixture.service.uploadDocument(fixture.kbId, file);

        assertThat(response.getDocumentId()).isEqualTo(fixture.documentId);
        assertThat(response.getIndexAction()).isEqualTo("UNCHANGED");
        assertThat(response.getReusedChunkCount()).isEqualTo(4);
        assertThat(response.getEmbeddedChunkCount()).isZero();
        verify(fixture.storage, never()).saveFile(any(), any(), any());
        verify(fixture.indexService, never()).replaceIndex(any(), anyBoolean(), any());
    }

    @Test
    void updatesSameLogicalDocumentAndReturnsReuseCounters() throws Exception {
        Fixture fixture = new Fixture();
        MockMultipartFile file = new MockMultipartFile(
                "file", "Notes.txt", "text/plain", "changed content".getBytes());
        Document existing = Document.builder()
                .id(fixture.documentId)
                .kbId(fixture.kbId)
                .filename("Notes.txt")
                .sourceKey("notes.txt")
                .metadata("{\"filePath\":\"old.txt\"}")
                .contentHash(DocumentHashing.sha256("old content"))
                .indexVersion(3)
                .indexStatus("READY")
                .chunkCount(2)
                .build();
        when(fixture.documentMapper.selectByKbIdAndSourceKey(fixture.kbId, "notes.txt"))
                .thenReturn(existing);
        when(fixture.storage.saveFile(eq(fixture.kbId), eq(fixture.documentId), eq(file)))
                .thenReturn("new.txt");
        when(fixture.storage.getFilePath("new.txt")).thenReturn(Path.of("new.txt"));
        when(fixture.parser.parse(any(Path.class), eq("txt"))).thenReturn(
                DocumentParserService.ParsedDocument.builder()
                        .content("changed content")
                        .metadata(Map.of("parser", "tika"))
                        .fileType("txt")
                        .estimatedCharacterCount(15)
                        .build());
        when(fixture.indexService.replaceIndex(eq(existing), eq(false), any())).thenReturn(
                new IncrementalDocumentIndexService.IndexResult(2, 1, 1));

        CreateDocumentResponse response = fixture.service.uploadDocument(fixture.kbId, file);

        assertThat(response.getIndexAction()).isEqualTo("UPDATED");
        assertThat(response.getIndexVersion()).isEqualTo(4);
        assertThat(response.getReusedChunkCount()).isEqualTo(1);
        assertThat(response.getEmbeddedChunkCount()).isEqualTo(1);
        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(fixture.indexService).replaceIndex(document.capture(), eq(false), any());
        assertThat(document.getValue().getContentHash())
                .isEqualTo(DocumentHashing.sha256("changed content"));
        assertThat(document.getValue().getIndexVersion()).isEqualTo(4);
        verify(fixture.storage).deleteFile("old.txt");
    }

    private static class Fixture {
        private final String kbId = "11111111-1111-1111-1111-111111111111";
        private final String documentId = "22222222-2222-2222-2222-222222222222";
        private final String indexFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        private final DocumentMapper documentMapper = mock(DocumentMapper.class);
        private final DocumentConverter converter = mock(DocumentConverter.class);
        private final DocumentStorageService storage = mock(DocumentStorageService.class);
        private final MarkdownParserService markdown = mock(MarkdownParserService.class);
        private final DocumentParserService parser = mock(DocumentParserService.class);
        private final IncrementalDocumentIndexService indexService = mock(IncrementalDocumentIndexService.class);
        private final ResourceAccessService access = mock(ResourceAccessService.class);
        private final DocumentFacadeServiceImpl service = new DocumentFacadeServiceImpl(
                documentMapper, converter, storage, markdown, parser, indexService, access);

        private Fixture() {
            when(indexService.currentFingerprint()).thenReturn(indexFingerprint);
        }
    }
}
