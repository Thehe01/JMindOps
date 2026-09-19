package com.kama.jmindops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.model.dto.DocumentDTO;
import com.kama.jmindops.model.entity.Document;
import com.kama.jmindops.model.entity.DocumentIndexTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.mapper.DocumentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentIndexTaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(DocumentIndexTaskExecutor.class);
    private static final int MAX_CHUNK_CHARS = 2_000;
    private static final int CHUNK_OVERLAP_CHARS = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DocumentStorageService documentStorageService;
    private final MarkdownParserService markdownParserService;
    private final DocumentParserService documentParserService;
    private final IncrementalDocumentIndexService incrementalIndexService;
    private final DocumentMapper documentMapper;

    public DocumentIndexTaskExecutor(
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            DocumentParserService documentParserService,
            IncrementalDocumentIndexService incrementalIndexService
    ) {
        this(documentStorageService, markdownParserService, documentParserService, incrementalIndexService, null);
    }

    @Autowired
    public DocumentIndexTaskExecutor(
            DocumentStorageService documentStorageService,
            MarkdownParserService markdownParserService,
            DocumentParserService documentParserService,
            IncrementalDocumentIndexService incrementalIndexService,
            @Autowired(required = false) DocumentMapper documentMapper
    ) {
        this.documentStorageService = documentStorageService;
        this.markdownParserService = markdownParserService;
        this.documentParserService = documentParserService;
        this.incrementalIndexService = incrementalIndexService;
        this.documentMapper = documentMapper;
    }

    public ExecutionResult execute(DocumentIndexTask task) throws IOException {
        // Idempotency check: if the target document has already been updated to this version and is READY,
        // we skip re-embedding to avoid duplicate-key or optimistic lock conflicts on recovery retries.
        if (documentMapper != null) {
            Document existingInDb = documentMapper.selectById(task.getDocumentId());
            if (existingInDb != null
                    && existingInDb.getIndexVersion() != null
                    && existingInDb.getIndexVersion().equals(task.getIndexVersion())
                    && "READY".equals(existingInDb.getIndexStatus())
                    && task.getContentHash().equals(existingInDb.getContentHash())
                    && task.getIndexFingerprint().equals(existingInDb.getIndexFingerprint())) {
                log.info("文档索引版本已生效，无需重复执行: documentId={}, version={}",
                        task.getDocumentId(), task.getIndexVersion());
                cleanupOldFile(task);
                int chunkCount = existingInDb.getChunkCount() != null ? existingInDb.getChunkCount() : 0;
                return new ExecutionResult(chunkCount, chunkCount, 0);
            }
        }

        List<IncrementalDocumentIndexService.ChunkInput> chunks = parseDocument(task.getFilePath(), task.getFiletype());
        if (chunks.isEmpty()) {
            throw new BizException("文档中没有可索引的文本内容");
        }

        DocumentDTO.MetaData metadata = new DocumentDTO.MetaData();
        metadata.setFilePath(task.getFilePath());

        LocalDateTime now = LocalDateTime.now();
        Document document = Document.builder()
                .id(task.getDocumentId())
                .kbId(task.getKbId())
                .filename(task.getFilename())
                .sourceKey(task.getSourceKey())
                .filetype(task.getFiletype())
                .size(task.getFileSize())
                .metadata(OBJECT_MAPPER.writeValueAsString(metadata))
                .contentHash(task.getContentHash())
                .indexFingerprint(task.getIndexFingerprint())
                .indexVersion(task.getIndexVersion())
                .createdAt(task.getCreatedAt() == null ? now : task.getCreatedAt())
                .updatedAt(now)
                .build();

        boolean isNew = task.isNewDocument();
        if (documentMapper != null) {
            Document existingInDb = documentMapper.selectById(task.getDocumentId());
            if (existingInDb != null) {
                isNew = false;
            }
        }

        IncrementalDocumentIndexService.IndexResult indexResult =
                incrementalIndexService.replaceIndex(document, isNew, chunks);

        cleanupOldFile(task);

        return new ExecutionResult(indexResult.chunkCount(), indexResult.reusedChunkCount(), indexResult.embeddedChunkCount());
    }

    private void cleanupOldFile(DocumentIndexTask task) {
        if (task.getOldFilePath() != null && !task.getOldFilePath().equals(task.getFilePath())) {
            try {
                documentStorageService.deleteFile(task.getOldFilePath());
            } catch (Exception cleanupError) {
                log.warn("新索引已生效，但旧文件清理失败: documentId={}, oldPath={}",
                        task.getDocumentId(), task.getOldFilePath());
            }
        }
    }

    public List<IncrementalDocumentIndexService.ChunkInput> parseDocument(String filePath, String filetype)
            throws IOException {
        if ("md".equalsIgnoreCase(filetype) || "markdown".equalsIgnoreCase(filetype)) {
            return processMarkdownDocument(filePath);
        }
        return processGeneralDocument(filePath, filetype);
    }

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

    private String safeJsonString(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
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

    public record ExecutionResult(int chunkCount, int reusedChunkCount, int embeddedChunkCount) {}
}
