package com.kama.jmindops.service;

import lombok.Data;
import lombok.ToString;

import java.io.InputStream;
import java.util.List;

/**
 * Markdown 解析服务接口
 */
public interface MarkdownParserService {
    List<MarkdownSection> parseMarkdown(InputStream inputStream);

    @Data
    @ToString
    class MarkdownSection {
        private String title;
        private String content;

        public MarkdownSection() {}
        public MarkdownSection(String title, String content) {
            this.title = title;
            this.content = content;
        }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
