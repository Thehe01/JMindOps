package com.kama.jmindops.service;

import lombok.Builder;
import lombok.Data;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * 统一多格式文档解析服务接口
 * 支持 PDF, Word(DOC/DOCX), PPT(PPT/PPTX), TXT, Markdown 等格式的结构化文本与元数据提取
 */
public interface DocumentParserService {

    @Data
    @Builder
    class ParsedDocument {
        private String content;
        private Map<String, String> metadata;
        private String fileType;
        private int estimatedCharacterCount;

        public ParsedDocument() {}
        public ParsedDocument(String content, Map<String, String> metadata, String fileType, int estimatedCharacterCount) {
            this.content = content;
            this.metadata = metadata;
            this.fileType = fileType;
            this.estimatedCharacterCount = estimatedCharacterCount;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        public int getEstimatedCharacterCount() { return estimatedCharacterCount; }
        public void setEstimatedCharacterCount(int estimatedCharacterCount) { this.estimatedCharacterCount = estimatedCharacterCount; }

        public static ParsedDocumentBuilder builder() { return new ParsedDocumentBuilder(); }
        public static class ParsedDocumentBuilder {
            private String content;
            private Map<String, String> metadata;
            private String fileType;
            private int estimatedCharacterCount;

            public ParsedDocumentBuilder() {}
            public ParsedDocumentBuilder content(String content) { this.content = content; return this; }
            public ParsedDocumentBuilder metadata(Map<String, String> metadata) { this.metadata = metadata; return this; }
            public ParsedDocumentBuilder fileType(String fileType) { this.fileType = fileType; return this; }
            public ParsedDocumentBuilder estimatedCharacterCount(int estimatedCharacterCount) { this.estimatedCharacterCount = estimatedCharacterCount; return this; }
            public ParsedDocument build() { return new ParsedDocument(content, metadata, fileType, estimatedCharacterCount); }
        }
    }

    ParsedDocument parse(InputStream inputStream, String filename, String filetype);
    ParsedDocument parse(Path filePath, String filetype);
    boolean isSupported(String filetype);
}
