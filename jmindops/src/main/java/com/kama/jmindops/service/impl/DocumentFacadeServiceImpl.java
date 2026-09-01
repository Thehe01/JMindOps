package com.kama.jmindops.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.converter.DocumentConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.dto.DocumentDTO;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.request.CreateDocumentRequest;
import com.kama.jmindops.model.request.UpdateDocumentRequest;
import com.kama.jmindops.model.response.CreateDocumentResponse;
import com.kama.jmindops.model.response.GetDocumentsResponse;
import com.kama.jmindops.model.vo.DocumentVO;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.DocumentFacadeService;
import com.kama.jmindops.service.DocumentParserService;
import com.kama.jmindops.service.DocumentStorageService;
import com.kama.jmindops.service.MarkdownParserService;
import com.kama.jmindops.service.RagService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DocumentFacadeServiceImpl implements DocumentFacadeService {
    private static final Logger log = LoggerFactory.getLogger(DocumentFacadeServiceImpl.class);


    private static final Set<String> SUPPORTED_FILE_TYPES = Set.of(
            "md", "markdown", "txt", "pdf", "docx", "doc", "pptx", "ppt", "html", "htm"
    );

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "text/plain", "text/markdown", "application/octet-stream",
            "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/html"
    );

    private static final int MAX_CHUNK_CHARS = 2_000;
    private static final int CHUNK_OVERLAP_CHARS = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentMapper documentMapper;
    private final DocumentConverter documentConverter;
    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final DocumentParserService documentParserService;
    private final RagService ragService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ResourceAccessService resourceAccessService;
    public DocumentFacadeServiceImpl(DocumentMapper documentMapper, DocumentConverter documentConverter, DocumentStorageService documentStorageService, MarkdownParserService markdownParserService, DocumentParserService documentParserService, RagService ragService, ChunkBgeM3Mapper chunkBgeM3Mapper, ResourceAccessService resourceAccessService) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
        this.documentParserService = documentParserService;
        this.ragService = ragService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.resourceAccessService = resourceAccessService;
    }


    @Override
    public GetDocumentsResponse getDocuments() {
        List<Document> documents = documentMapper.selectAllByOwner(resourceAccessService.currentUserId());
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public GetDocumentsResponse getDocumentsByKbId(String kbId) {
        resourceAccessService.requireOwnedKnowledgeBase(kbId);
        List<Document> documents = documentMapper.selectByKbId(kbId);
        List<DocumentVO> result = new ArrayList<>();
        for (Document document : documents) {
            try {
                DocumentVO vo = documentConverter.toVO(document);
                result.add(vo);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
        return GetDocumentsResponse.builder()
                .documents(result.toArray(new DocumentVO[0]))
                .build();
    }

    @Override
    public CreateDocumentResponse createDocument(CreateDocumentRequest request) {
        try {
            resourceAccessService.requireOwnedKnowledgeBase(request.getKbId());
            DocumentDTO documentDTO = documentConverter.toDTO(request);
            Document document = documentConverter.toEntity(documentDTO);

            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        String documentId = null;
        String filePath = null;
        try {
            resourceAccessService.requireOwnedKnowledgeBase(kbId);
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            // 提取文件信息
            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();
            validateUpload(file, originalFilename, filetype);

            // 创建文档记录（先创建记录，获取 documentId）
            DocumentDTO documentDTO = DocumentDTO.builder()
                    .kbId(kbId)
                    .filename(originalFilename)
                    .filetype(filetype)
                    .size(fileSize)
                    .build();

            Document document = documentConverter.toEntity(documentDTO);
            LocalDateTime now = LocalDateTime.now();
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            // 插入数据库，获取生成的 documentId
            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档记录失败");
            }

            documentId = document.getId();

            // 保存文件
            filePath = documentStorageService.saveFile(kbId, documentId, file);

            // 更新文档记录，保存文件路径到 metadata
            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(filePath);
            documentDTO.setMetadata(metadata);
            documentDTO.setId(documentId);
            documentDTO.setCreatedAt(now);
            documentDTO.setUpdatedAt(now);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(documentId);
            updatedDocument.setCreatedAt(now);
            updatedDocument.setUpdatedAt(now);

            documentMapper.updateById(updatedDocument);

            log.info("文档文件已保存: kbId={}, documentId={}, type={}, size={}",
                    kbId, documentId, filetype, fileSize);

            int chunkCount;
            if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
                chunkCount = processMarkdownDocument(kbId, documentId, filePath);
            } else {
                chunkCount = processGeneralDocument(kbId, documentId, filePath, filetype);
            }
            if (chunkCount <= 0) {
                throw new BizException("文档中没有可索引的文本内容");
            }

            log.info("文档处理完成: kbId={}, documentId={}, chunkCount={}", kbId, documentId, chunkCount);
            return CreateDocumentResponse.builder()
                    .documentId(documentId)
                    .build();
        } catch (Exception e) {
            compensateFailedUpload(documentId, filePath);
            if (e instanceof BizException bizException) {
                throw bizException;
            }
            log.error("文档上传或索引失败: exceptionType={}", e.getClass().getSimpleName());
            log.debug("文档上传或索引失败详情", e);
            throw new BizException("文档上传或索引失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteDocument(String documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BizException("文档不存在: " + documentId);
        }
        resourceAccessService.requireOwnedKnowledgeBase(document.getKbId());

        String storedFilePath = null;
        try {
            DocumentDTO documentDTO = documentConverter.toDTO(document);
            if (documentDTO.getMetadata() != null && documentDTO.getMetadata().getFilePath() != null) {
                storedFilePath = documentDTO.getMetadata().getFilePath();
            }
        } catch (Exception e) {
            log.warn("读取文档存储信息失败，继续删除文档记录: documentId={}", documentId);
        }

        // 先删除数据库及级联 chunk
        int result = documentMapper.deleteById(documentId);
        if (result <= 0) {
            throw new BizException("删除文档失败");
        }
        if (storedFilePath != null) {
            try {
                documentStorageService.deleteFile(storedFilePath);
            } catch (Exception e) {
                log.warn("文档记录已删除，但物理文件清理失败: documentId={}, exceptionType={}",
                        documentId, e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 处理 Markdown 文档，基于 AST 章节结构切块
     */
    private int processMarkdownDocument(String kbId, String documentId, String filePath) throws IOException {
        log.info("开始处理 Markdown 文档: kbId={}, documentId={}", kbId, documentId);

        Path path = documentStorageService.getFilePath(filePath);
        try (InputStream inputStream = Files.newInputStream(path)) {
            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
            if (sections.isEmpty()) {
                return 0;
            }

            LocalDateTime now = LocalDateTime.now();
            int chunkCount = 0;

            for (MarkdownParserService.MarkdownSection section : sections) {
                String title = section.getTitle();
                String content = section.getContent();

                if (title == null || title.trim().isEmpty()) {
                    continue;
                }

                String sectionText = "# " + title + "\n\n" + (content == null ? "" : content.trim());
                for (String chunkContent : splitIntoChunks(sectionText)) {
                    float[] embedding = ragService.embed(chunkContent);
                    ChunkBgeM3 chunk = ChunkBgeM3.builder()
                            .kbId(kbId)
                            .docId(documentId)
                            .content(chunkContent)
                            .metadata("{\"type\":\"markdown\",\"title\":" + safeJsonString(title) + "}")
                            .embedding(embedding)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

                    int result = chunkBgeM3Mapper.insert(chunk);
                    if (result > 0) {
                        chunkCount++;
                    } else {
                        throw new BizException("写入文档向量失败");
                    }
                }
            }
            return chunkCount;
        }
    }

    /**
     * 处理通用多格式文档 (PDF, Word, PPT, TXT, HTML 等)，基于 Apache Tika 提取与智能分块
     */
    private int processGeneralDocument(String kbId, String documentId, String filePath, String filetype) {
        log.info("开始使用 Tika 处理通用文档: kbId={}, documentId={}, type={}", kbId, documentId, filetype);

        Path path = documentStorageService.getFilePath(filePath);
        DocumentParserService.ParsedDocument parsed = documentParserService.parse(path, filetype);

        String textContent = parsed.getContent();
        if (textContent == null || textContent.trim().isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int chunkCount = 0;
        List<String> chunks = splitIntoChunks(textContent);

        Map<String, Object> baseMeta = new HashMap<>();
        baseMeta.put("type", filetype);
        if (parsed.getMetadata() != null) {
            baseMeta.putAll(parsed.getMetadata());
        }

        String metadataJson;
        try {
            metadataJson = OBJECT_MAPPER.writeValueAsString(baseMeta);
        } catch (Exception e) {
            metadataJson = "{\"type\":\"" + filetype + "\"}";
        }

        for (String chunkContent : chunks) {
            float[] embedding = ragService.embed(chunkContent);
            ChunkBgeM3 chunk = ChunkBgeM3.builder()
                    .kbId(kbId)
                    .docId(documentId)
                    .content(chunkContent)
                    .metadata(metadataJson)
                    .embedding(embedding)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            int result = chunkBgeM3Mapper.insert(chunk);
            if (result > 0) {
                chunkCount++;
            } else {
                throw new BizException("写入文档向量失败");
            }
        }
        return chunkCount;
    }

    private String safeJsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * 从文件名提取文件类型
     */
    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
    }

    private void validateUpload(MultipartFile file, String originalFilename, String filetype) throws IOException {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException("文件名不能为空");
        }
        if (!SUPPORTED_FILE_TYPES.contains(filetype.toLowerCase(Locale.ROOT))) {
            throw new BizException("不支持的文件格式，仅支持: " + String.join(", ", SUPPORTED_FILE_TYPES));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !SUPPORTED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            log.warn("拒绝不安全的 Content-Type: filename={}, contentType={}", originalFilename, contentType);
            throw new BizException("上传失败：检测到不受支持的 MIME 类型或伪造的 Content-Type: " + contentType);
        }
        // 仅对纯文本格式进行二进制 NUL 字符检查
        if ("txt".equalsIgnoreCase(filetype) || "text".equalsIgnoreCase(filetype)) {
            try (InputStream inputStream = file.getInputStream()) {
                byte[] sample = inputStream.readNBytes(8192);
                for (byte value : sample) {
                    if (value == 0) {
                        throw new BizException("文本文件包含二进制内容，无法作为纯文本处理");
                    }
                }
            }
        }
    }

    private void compensateFailedUpload(String documentId, String filePath) {
        if (documentId != null) {
            try {
                documentMapper.deleteById(documentId);
            } catch (Exception cleanupError) {
                log.error("清理失败文档记录失败: documentId={}, exceptionType={}",
                        documentId, cleanupError.getClass().getSimpleName());
            }
        }
        if (filePath != null) {
            try {
                documentStorageService.deleteFile(filePath);
            } catch (Exception cleanupError) {
                log.error("清理失败上传文件失败: documentId={}, exceptionType={}",
                        documentId, cleanupError.getClass().getSimpleName());
            }
        }
    }

    private List<String> splitIntoChunks(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.length() <= MAX_CHUNK_CHARS) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(normalized.length(), start + MAX_CHUNK_CHARS);
            int end = hardEnd;
            if (hardEnd < normalized.length()) {
                int newline = normalized.lastIndexOf('\n', hardEnd);
                int whitespace = normalized.lastIndexOf(' ', hardEnd);
                int naturalBreak = Math.max(newline, whitespace);
                if (naturalBreak > start + MAX_CHUNK_CHARS / 2) {
                    end = naturalBreak;
                }
            }
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - CHUNK_OVERLAP_CHARS);
        }
        return chunks;
    }

    @Override
    public void updateDocument(String documentId, UpdateDocumentRequest request) {
        try {
            Document existingDocument = documentMapper.selectById(documentId);
            if (existingDocument == null) {
                throw new BizException("文档不存在: " + documentId);
            }
            resourceAccessService.requireOwnedKnowledgeBase(existingDocument.getKbId());

            DocumentDTO documentDTO = documentConverter.toDTO(existingDocument);
            documentConverter.updateDTOFromRequest(documentDTO, request);

            Document updatedDocument = documentConverter.toEntity(documentDTO);
            updatedDocument.setId(existingDocument.getId());
            updatedDocument.setKbId(existingDocument.getKbId());
            updatedDocument.setCreatedAt(existingDocument.getCreatedAt());
            updatedDocument.setUpdatedAt(LocalDateTime.now());

            int result = documentMapper.updateById(updatedDocument);
            if (result <= 0) {
                throw new BizException("更新文档失败");
            }
        } catch (JsonProcessingException e) {
            throw new BizException("更新文档时发生序列化错误: " + e.getMessage());
        }
    }
}
