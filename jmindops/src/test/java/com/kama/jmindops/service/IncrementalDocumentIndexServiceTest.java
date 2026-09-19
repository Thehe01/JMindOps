package com.kama.jmindops.service;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncrementalDocumentIndexServiceTest {
    @Test
    void reusesUnchangedChunkEmbeddingAndEmbedsOnlyNewContent() {
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        RagService ragService = mock(RagService.class);
        DocumentIndexStore indexStore = mock(DocumentIndexStore.class);
        DocumentIndexFingerprint fingerprint = new DocumentIndexFingerprint(
                "bge-m3", 1024, "none", "test-v1");
        String documentId = "11111111-1111-1111-1111-111111111111";
        String unchanged = "unchanged chunk";
        float[] oldEmbedding = new float[]{1.0f, 2.0f};
        when(chunkMapper.selectByDocumentId(documentId)).thenReturn(List.of(
                ChunkBgeM3.builder()
                        .id("22222222-2222-2222-2222-222222222222")
                        .content(unchanged)
                        .chunkHash(DocumentHashing.sha256(unchanged))
                        .indexFingerprint(fingerprint.current())
                        .embedding(oldEmbedding)
                        .createdAt(LocalDateTime.now().minusDays(1))
                        .build()
        ));
        when(ragService.embed("new chunk")).thenReturn(new float[]{3.0f, 4.0f});
        Document document = Document.builder()
                .id(documentId)
                .kbId("33333333-3333-3333-3333-333333333333")
                .indexVersion(2)
                .build();
        IncrementalDocumentIndexService service =
                new IncrementalDocumentIndexService(chunkMapper, ragService, indexStore, fingerprint);

        IncrementalDocumentIndexService.IndexResult result = service.replaceIndex(
                document,
                false,
                List.of(
                        new IncrementalDocumentIndexService.ChunkInput(unchanged, "{}"),
                        new IncrementalDocumentIndexService.ChunkInput("new chunk", "{}")
                ));

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.reusedChunkCount()).isEqualTo(1);
        assertThat(result.embeddedChunkCount()).isEqualTo(1);
        verify(ragService, never()).embed(unchanged);
        verify(ragService).embed("new chunk");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkBgeM3>> chunks = ArgumentCaptor.forClass(List.class);
        verify(indexStore).replace(org.mockito.ArgumentMatchers.same(document),
                org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.anyInt(), chunks.capture());
        assertThat(chunks.getValue().get(0).getId())
                .isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(chunks.getValue().get(0).getEmbedding()).isSameAs(oldEmbedding);
        assertThat(chunks.getValue().get(1).getId()).isNotBlank();
        assertThat(chunks.getValue()).extracting(ChunkBgeM3::getChunkIndex)
                .containsExactly(0, 1);
    }

    @Test
    void embedsEveryChunkForANewDocument() {
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        RagService ragService = mock(RagService.class);
        DocumentIndexStore indexStore = mock(DocumentIndexStore.class);
        DocumentIndexFingerprint fingerprint = new DocumentIndexFingerprint(
                "bge-m3", 1024, "none", "test-v1");
        when(ragService.embed(anyString())).thenReturn(new float[]{1.0f});
        Document document = Document.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .kbId("33333333-3333-3333-3333-333333333333")
                .indexVersion(1)
                .build();
        IncrementalDocumentIndexService service =
                new IncrementalDocumentIndexService(chunkMapper, ragService, indexStore, fingerprint);

        IncrementalDocumentIndexService.IndexResult result = service.replaceIndex(
                document, true,
                List.of(new IncrementalDocumentIndexService.ChunkInput("only", "{}")));

        assertThat(result.reusedChunkCount()).isZero();
        assertThat(result.embeddedChunkCount()).isEqualTo(1);
        verify(chunkMapper, never()).selectByDocumentId(anyString());
    }

    @Test
    void doesNotReuseEmbeddingFromAnotherIndexPipeline() {
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        RagService ragService = mock(RagService.class);
        DocumentIndexStore indexStore = mock(DocumentIndexStore.class);
        DocumentIndexFingerprint fingerprint = new DocumentIndexFingerprint(
                "new-model", 1024, "none", "test-v2");
        String documentId = "11111111-1111-1111-1111-111111111111";
        when(chunkMapper.selectByDocumentId(documentId)).thenReturn(List.of(
                ChunkBgeM3.builder()
                        .id("22222222-2222-2222-2222-222222222222")
                        .content("same chunk")
                        .chunkHash(DocumentHashing.sha256("same chunk"))
                        .indexFingerprint("legacy-or-different-fingerprint")
                        .embedding(new float[]{1.0f})
                        .build()));
        when(ragService.embed("same chunk")).thenReturn(new float[]{2.0f});
        Document document = Document.builder()
                .id(documentId)
                .kbId("33333333-3333-3333-3333-333333333333")
                .indexVersion(2)
                .build();
        IncrementalDocumentIndexService service =
                new IncrementalDocumentIndexService(chunkMapper, ragService, indexStore, fingerprint);

        IncrementalDocumentIndexService.IndexResult result = service.replaceIndex(
                document, false,
                List.of(new IncrementalDocumentIndexService.ChunkInput("same chunk", "{}")));

        assertThat(result.reusedChunkCount()).isZero();
        assertThat(result.embeddedChunkCount()).isEqualTo(1);
        verify(ragService).embed("same chunk");
        assertThat(document.getIndexFingerprint()).isEqualTo(fingerprint.current());
    }
}
