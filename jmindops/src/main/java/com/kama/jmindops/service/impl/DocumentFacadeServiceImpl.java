package com.kama.jmindops.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.converter.DocumentConverter;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.mapper.DocumentMapper;
import com.kama.jmindops.model.dto.DocumentDTO;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.request.CreateDocumentRequest;
import com.kama.jmindops.model.request.UpdateDocumentRequest;
import com.kama.jmindops.model.response.CreateDocumentResponse;
import com.kama.jmindops.model.response.GetDocumentsResponse;
import com.kama.jmindops.model.vo.DocumentVO;
import com.kama.jmindops.security.ResourceAccessService;
import com.kama.jmindops.service.DocumentFacadeService;
import com.kama.jmindops.service.DocumentHashing;
import com.kama.jmindops.service.IncrementalDocumentIndexService;
import com.kama.jmindops.service.DocumentParserService;
import com.kama.jmindops.service.DocumentStorageService;
import com.kama.jmindops.service.MarkdownParserService;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import com.kama.jmindops.model.entity.DocumentIndexTaskStatus;
import com.kama.jmindops.service.DocumentIndexTaskStore;
import com.kama.jmindops.service.DocumentIndexTaskWorker;
import com.kama.jmindops.service.IndexRetryPolicy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final IncrementalDocumentIndexService incrementalIndexService;
    private final ResourceAccessService resourceAccessService;
    private final DocumentIndexTaskStore documentIndexTaskStore;
    private final DocumentIndexTaskWorker documentIndexTaskWorker;
    private final IndexRetryPolicy indexRetryPolicy;

    public DocumentFacadeServiceImpl(
            DocumentMapper documentMapper,
            DocumentConverter documentConverter,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            DocumentParserService documentParserService,
            IncrementalDocumentIndexService incrementalIndexService,
            ResourceAccessService resourceAccessService
    ) {
        this(documentMapper, documentConverter, documentStorageService, markdownParserService,
                documentParserService, incrementalIndexService, resourceAccessService,
                null, null, null);
    }

    @Autowired
    public DocumentFacadeServiceImpl(
            DocumentMapper documentMapper,
            DocumentConverter documentConverter,
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            DocumentParserService documentParserService,
            IncrementalDocumentIndexService incrementalIndexService,
            ResourceAccessService resourceAccessService,
            DocumentIndexTaskStore documentIndexTaskStore,
            DocumentIndexTaskWorker documentIndexTaskWorker,
            IndexRetryPolicy indexRetryPolicy
    ) {
        this.documentMapper = documentMapper;
        this.documentConverter = documentConverter;
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
        this.documentParserService = documentParserService;
        this.incrementalIndexService = incrementalIndexService;
        this.resourceAccessService = resourceAccessService;
        this.documentIndexTaskStore = documentIndexTaskStore;
        this.documentIndexTaskWorker = documentIndexTaskWorker;
        this.indexRetryPolicy = indexRetryPolicy;
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
            document.setId(UUID.randomUUID().toString());
            document.setSourceKey(DocumentHashing.sourceKey(document.getFilename()));
            document.setIndexVersion(0);
            document.setIndexStatus("EMPTY");
            document.setChunkCount(0);
            document.setCreatedAt(now);
            document.setUpdatedAt(now);

            int result = documentMapper.insert(document);
            if (result <= 0) {
                throw new BizException("创建文档失败");
            }

            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .indexAction("CREATED_EMPTY")
                    .indexVersion(0)
                    .chunkCount(0)
                    .reusedChunkCount(0)
                    .embeddedChunkCount(0)
                    .build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建文档时发生序列化错误: " + e.getMessage());
        }
    }

    @Override
    public CreateDocumentResponse uploadDocument(String kbId, MultipartFile file) {
        String newFilePath = null;
        try {
            resourceAccessService.requireOwnedKnowledgeBase(kbId);
            if (file.isEmpty()) {
                throw new BizException("上传的文件为空");
            }

            String originalFilename = file.getOriginalFilename();
            String filetype = getFileType(originalFilename);
            long fileSize = file.getSize();
            validateUpload(file, originalFilename, filetype);
            String sourceKey = DocumentHashing.sourceKey(originalFilename);
            String contentHash;
            try (InputStream input = file.getInputStream()) {
                contentHash = DocumentHashing.sha256(input);
            }

            Document existing = documentMapper.selectByKbIdAndSourceKey(kbId, sourceKey);
            String indexFingerprint = incrementalIndexService.currentFingerprint();
            if (existing != null
                    && contentHash.equals(existing.getContentHash())
                    && indexFingerprint.equals(existing.getIndexFingerprint())
                    && "READY".equals(existing.getIndexStatus())) {
                log.info("文档内容未变化，跳过解析和向量化: kbId={}, documentId={}",
                        kbId, existing.getId());
                return CreateDocumentResponse.builder()
                        .documentId(existing.getId())
                        .indexAction("UNCHANGED")
                        .indexVersion(existing.getIndexVersion())
                        .chunkCount(existing.getChunkCount())
                        .reusedChunkCount(existing.getChunkCount())
                        .embeddedChunkCount(0)
                        .build();
            }

            boolean newDocument = existing == null;
            String documentId;
            int nextVersion;

            if (documentIndexTaskStore != null) {
                Optional<DocumentIndexTask> activeTaskOpt =
                        documentIndexTaskStore.findLatestActiveByKbIdAndSourceKey(kbId, sourceKey);
                if (activeTaskOpt.isPresent()) {
                    DocumentIndexTask activeTask = activeTaskOpt.get();
                    if (contentHash.equals(activeTask.getContentHash())
                            && indexFingerprint.equals(activeTask.getIndexFingerprint())) {
                        log.info("文档存在进行中的相同索引任务，跳过重复创建: kbId={}, documentId={}, taskId={}",
                                kbId, activeTask.getDocumentId(), activeTask.getId());
                        return CreateDocumentResponse.builder()
                                .documentId(activeTask.getDocumentId())
                                .indexAction("UNCHANGED")
                                .indexVersion(activeTask.getIndexVersion())
                                .chunkCount(0)
                                .reusedChunkCount(0)
                                .embeddedChunkCount(0)
                                .build();
                    }
                    // In-flight task exists for this logical document: reuse its documentId and advance version
                    documentId = activeTask.getDocumentId();
                    nextVersion = activeTask.getIndexVersion() + 1;
                    newDocument = false;
                } else if (newDocument) {
                    documentId = UUID.randomUUID().toString();
                    nextVersion = 1;
                } else {
                    documentId = existing.getId();
                    nextVersion = existing.getIndexVersion() == null ? 1 : existing.getIndexVersion() + 1;
                }
            } else {
                if (newDocument) {
                    documentId = UUID.randomUUID().toString();
                    nextVersion = 1;
                } else {
                    documentId = existing.getId();
                    nextVersion = existing.getIndexVersion() == null ? 1 : existing.getIndexVersion() + 1;
                }
            }

            LocalDateTime now = LocalDateTime.now();
            Document document = newDocument ? new Document() : (existing != null ? existing : new Document());
            document.setId(documentId);
            document.setKbId(kbId);
            if (newDocument) {
                document.setCreatedAt(now);
            }
            String oldFilePath = storedFilePath(existing);

            newFilePath = documentStorageService.saveFile(kbId, documentId, file);

            if (documentIndexTaskStore != null) {
                DocumentIndexTask task = DocumentIndexTask.builder()
                        .id(UUID.randomUUID().toString())
                        .kbId(kbId)
                        .documentId(documentId)
                        .indexVersion(nextVersion)
                        .status(DocumentIndexTaskStatus.PENDING)
                        .retryCount(0)
                        .maxRetries(indexRetryPolicy != null ? indexRetryPolicy.getMaxRetries() : 3)
                        .filePath(newFilePath)
                        .filename(originalFilename)
                        .filetype(filetype)
                        .fileSize(fileSize)
                        .contentHash(contentHash)
                        .sourceKey(sourceKey)
                        .indexFingerprint(indexFingerprint)
                        .isNewDocument(newDocument)
                        .oldFilePath(oldFilePath)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();

                documentIndexTaskStore.createTask(task);
                if (documentIndexTaskWorker != null) {
                    documentIndexTaskWorker.triggerAsync();
                }

                log.info("文档持久异步索引任务已创建: kbId={}, documentId={}, taskId={}, version={}",
                        kbId, documentId, task.getId(), nextVersion);

                return CreateDocumentResponse.builder()
                        .documentId(documentId)
                        .indexAction(newDocument ? "CREATED" : "UPDATED")
                        .indexVersion(nextVersion)
                        .chunkCount(0)
                        .reusedChunkCount(0)
                        .embeddedChunkCount(0)
                        .build();
            }

            List<IncrementalDocumentIndexService.ChunkInput> chunks = parseDocument(newFilePath, filetype);
            if (chunks.isEmpty()) {
                throw new BizException("文档中没有可索引的文本内容");
            }

            DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
            metadata.setFilePath(newFilePath);
            document.setFilename(originalFilename);
            document.setSourceKey(sourceKey);
            document.setFiletype(filetype);
            document.setSize(fileSize);
            document.setMetadata(OBJECT_MAPPER.writeValueAsString(metadata));
            document.setContentHash(contentHash);
            document.setIndexFingerprint(indexFingerprint);
            document.setIndexVersion(nextVersion);

            IncrementalDocumentIndexService.IndexResult indexResult =
                    incrementalIndexService.replaceIndex(document, newDocument, chunks);
            if (oldFilePath != null && !oldFilePath.equals(newFilePath)) {
                try {
                    documentStorageService.deleteFile(oldFilePath);
                } catch (Exception cleanupError) {
                    log.warn("新索引已生效，但旧文件清理失败: documentId={}", document.getId());
                }
            }

            log.info("文档增量索引完成: kbId={}, documentId={}, version={}, chunks={}, reused={}, embedded={}",
                    kbId, document.getId(), nextVersion, indexResult.chunkCount(),
                    indexResult.reusedChunkCount(), indexResult.embeddedChunkCount());
            return CreateDocumentResponse.builder()
                    .documentId(document.getId())
                    .indexAction(newDocument ? "CREATED" : "UPDATED")
                    .indexVersion(nextVersion)
                    .chunkCount(indexResult.chunkCount())
                    .reusedChunkCount(indexResult.reusedChunkCount())
                    .embeddedChunkCount(indexResult.embeddedChunkCount())
                    .build();
        } catch (Exception e) {
            cleanupNewFile(newFilePath);
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

        if (documentIndexTaskStore != null) {
            try {
                documentIndexTaskStore.deleteByDocumentId(documentId);
            } catch (Exception e) {
                log.warn("清理文档任务记录失败: documentId={}", documentId);
            }
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
    private List<IncrementalDocumentIndexService.ChunkInput> processMarkdownDocument(String filePath) throws IOException {
        Path path = documentStorageService.getFilePath(filePath);
        try (InputStream inputStream = Files.newInputStream(path)) {
            List<MarkdownParserService.MarkdownSection> sections = markdownParserService.parseMarkdown(inputStream);
            if (sections.isEmpty()) {
                return List.of();
            }
            List<IncrementalDocumentIndexService.ChunkInput> chunks = new ArrayList<>();
            for (MarkdownParserService.MarkdownSection section : sections) {
                String title = section.getTitle();
                String content = section.getContent();
                if (title == null || title.trim().isEmpty()) {
                    continue;
                }
                String sectionText = "# " + title + "\n\n" + (content == null ? "" : content.trim());
                for (String chunkContent : splitIntoChunks(sectionText)) {
                    chunks.add(new IncrementalDocumentIndexService.ChunkInput(
                            chunkContent,
                            "{\"type\":\"markdown\",\"title\":" + safeJsonString(title) + "}"
                    ));
                }
            }
            return chunks;
        }
    }

    /**
     * 处理通用多格式文档 (PDF, Word, PPT, TXT, HTML 等)，基于 Apache Tika 提取与智能分块
     */
    private List<IncrementalDocumentIndexService.ChunkInput> processGeneralDocument(String filePath, String filetype) {
        Path path = documentStorageService.getFilePath(filePath);
        DocumentParserService.ParsedDocument parsed = documentParserService.parse(path, filetype);
        String textContent = parsed.getContent();
        if (textContent == null || textContent.trim().isEmpty()) {
            return List.of();
        }
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

        final String finalMetadataJson = metadataJson;
        return chunks.stream()
                .map(content -> new IncrementalDocumentIndexService.ChunkInput(content, finalMetadataJson))
                .toList();
    }

    private List<IncrementalDocumentIndexService.ChunkInput> parseDocument(String filePath, String filetype)
            throws IOException {
        if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
            return processMarkdownDocument(filePath);
        }
        return processGeneralDocument(filePath, filetype);
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

    private String storedFilePath(Document document) {
        if (document == null || document.getMetadata() == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(document.getMetadata(), DocumentDTO.MetaData.class).getFilePath();
        } catch (Exception exception) {
            log.warn("无法解析旧文档文件路径: documentId={}", document.getId());
            return null;
        }
    }

    private void cleanupNewFile(String filePath) {
        if (filePath != null) {
            try {
                documentStorageService.deleteFile(filePath);
            } catch (Exception cleanupError) {
                log.error("清理失败上传文件失败: filePath={}, exceptionType={}",
                        filePath, cleanupError.getClass().getSimpleName());
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
            updatedDocument.setSourceKey(DocumentHashing.sourceKey(updatedDocument.getFilename()));
            updatedDocument.setContentHash(existingDocument.getContentHash());
            updatedDocument.setIndexVersion(existingDocument.getIndexVersion());
            updatedDocument.setIndexStatus(existingDocument.getIndexStatus());
            updatedDocument.setChunkCount(existingDocument.getChunkCount());
            updatedDocument.setIndexedAt(existingDocument.getIndexedAt());
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
