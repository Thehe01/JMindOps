package com.kama.jmindops.mapper;

import com.kama.jmindops.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jmindops.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int deleteByDocumentId(@Param("documentId") String documentId);

    List<ChunkBgeM3> selectByDocumentId(@Param("documentId") String documentId);

    int updateById(ChunkBgeM3 chunkBgeM3);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("indexFingerprint") String indexFingerprint,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> bm25Search(
            @Param("kbId") String kbId,
            @Param("query") String query,
            @Param("indexFingerprint") String indexFingerprint,
            @Param("limit") int limit
    );
}
