package com.kama.jmindops.service.impl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kama.jmindops.exception.BizException;
import com.kama.jmindops.service.DocumentParserService;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service

public class TikaDocumentParserServiceImpl implements DocumentParserService {
    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParserServiceImpl.class);


    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "docx", "doc", "pptx", "ppt", "txt", "md", "markdown", "html", "htm"
    );

    // 最大提取字符上限：20MB 字符，避免 BodyContentHandler 内存溢出
    private static final int MAX_EXTRACT_CHARS = 20 * 1024 * 1024;

    private final Parser parser = new AutoDetectParser();

    @Override
    public boolean isSupported(String filetype) {
        if (filetype == null || filetype.isBlank()) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(filetype.toLowerCase(Locale.ROOT).trim());
    }

    @Override
    public ParsedDocument parse(InputStream inputStream, String filename, String filetype) {
        String normalizedType = filetype == null ? "unknown" : filetype.toLowerCase(Locale.ROOT).trim();
        try (BufferedInputStream bis = new BufferedInputStream(inputStream)) {
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACT_CHARS);
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }

            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            parser.parse(bis, handler, metadata, context);

            String extractedText = handler.toString();
            String cleanedText = cleanExtractedText(extractedText);

            Map<String, String> metaMap = extractMetadata(metadata);
            metaMap.put("fileType", normalizedType);
            if (filename != null) {
                metaMap.put("filename", filename);
            }

            log.info("[TikaDocumentParser] Successfully parsed document: filename={}, type={}, rawLen={}, cleanLen={}",
                    filename, normalizedType, extractedText.length(), cleanedText.length());

            return ParsedDocument.builder()
                    .content(cleanedText)
                    .metadata(metaMap)
                    .fileType(normalizedType)
                    .estimatedCharacterCount(cleanedText.length())
                    .build();
        } catch (Exception e) {
            log.error("[TikaDocumentParser] Failed to parse document: filename={}, type={}", filename, normalizedType, e);
            throw new BizException("文档解析失败: " + e.getMessage());
        }
    }

    @Override
    public ParsedDocument parse(Path filePath, String filetype) {
        if (filePath == null || !Files.exists(filePath)) {
            throw new BizException("待解析的文件不存在: " + filePath);
        }
        String filename = filePath.getFileName().toString();
        try (InputStream is = Files.newInputStream(filePath)) {
            return parse(is, filename, filetype);
        } catch (Exception e) {
            log.error("[TikaDocumentParser] Failed to read file from path: {}", filePath, e);
            throw new BizException("读取文件解析失败: " + e.getMessage());
        }
    }

    private String cleanExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        // 统一换行符
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');

        // 替换不可见特殊控制字符（保留制表符与换行符）
        normalized = normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        // 消除过多的连续空行（最多保留 2 个连续换行）
        normalized = normalized.replaceAll("\n{3,}", "\n\n");

        return normalized.trim();
    }

    private Map<String, String> extractMetadata(Metadata metadata) {
        Map<String, String> result = new HashMap<>();
        if (metadata == null) {
            return result;
        }

        for (String name : metadata.names()) {
            String value = metadata.get(name);
            if (value != null && !value.isBlank() && value.length() < 500) {
                result.put(name, value);
            }
        }
        return result;
    }
}
