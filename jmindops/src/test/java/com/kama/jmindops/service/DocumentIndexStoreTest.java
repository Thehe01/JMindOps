package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexStoreTest {
    @Test
    void rejectsStaleIndexVersionBeforeDeletingVisibleChunks() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        ChunkBgeM3Mapper chunkMapper = mock(ChunkBgeM3Mapper.class);
        Document document = Document.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .indexVersion(4)
                .build();
        when(documentMapper.updateIndexByVersion(document, 3)).thenReturn(0);
        DocumentIndexStore store = new DocumentIndexStore(documentMapper, chunkMapper);

        assertThatThrownBy(() -> store.replace(document, false, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("其他请求更新");

        verify(chunkMapper, never()).deleteByDocumentId(document.getId());
    }
}
