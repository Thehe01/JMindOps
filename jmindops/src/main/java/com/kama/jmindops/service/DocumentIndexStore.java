package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 只负责一次原子索引切换：旧版本在新版本全部写入成功前始终可见。 */
@Service
public class DocumentIndexStore {
    private final DocumentMapper documentMapper;
    private final ChunkBgeM3Mapper chunkMapper;

    public DocumentIndexStore(DocumentMapper documentMapper, ChunkBgeM3Mapper chunkMapper) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public void replace(Document document, boolean newDocument, List<ChunkBgeM3> chunks) {
        int affected = newDocument
                ? documentMapper.insert(document)
                : documentMapper.updateIndexByVersion(document, document.getIndexVersion() - 1);
        if (affected != 1) {
            throw new BizException(newDocument
                    ? "创建文档记录失败"
                    : "文档已被其他请求更新，请重新上传最新版本");
        }
        if (!newDocument) {
            chunkMapper.deleteByDocumentId(document.getId());
        }
        for (ChunkBgeM3 chunk : chunks) {
            if (chunkMapper.insert(chunk) != 1) {
                throw new BizException("写入文档向量失败");
            }
        }
    }
}
