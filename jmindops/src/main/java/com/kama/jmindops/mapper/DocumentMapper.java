package com.kama.jmindops.mapper;

import com.kama.jmindops.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【document】的数据库操作Mapper
 * @createDate 2025-12-02 15:42:18
 * @Entity com.kama.jmindops.model.entity.Document
 */
@Mapper
public interface DocumentMapper {
    int insert(Document document);

    Document selectById(String id);

    List<Document> selectAll();

    List<Document> selectAllByOwner(String ownerId);

    List<Document> selectByKbId(String kbId);

    Document selectByKbIdAndSourceKey(
            @Param("kbId") String kbId,
            @Param("sourceKey") String sourceKey
    );

    int deleteById(String id);

    int updateById(Document document);

    int updateIndexByVersion(
            @Param("document") Document document,
            @Param("expectedVersion") int expectedVersion
    );
}
