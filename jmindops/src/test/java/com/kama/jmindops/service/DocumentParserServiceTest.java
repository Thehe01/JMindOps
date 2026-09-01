package com.kama.jmindops.service;

import com.kama.jmindops.service.impl.TikaDocumentParserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserServiceTest {

    private DocumentParserService documentParserService;

    @BeforeEach
    void setUp() {
        documentParserService = new TikaDocumentParserServiceImpl();
    }

    @Test
    void supportsCommonDocumentFormats() {
        assertThat(documentParserService.isSupported("pdf")).isTrue();
        assertThat(documentParserService.isSupported("docx")).isTrue();
        assertThat(documentParserService.isSupported("doc")).isTrue();
        assertThat(documentParserService.isSupported("pptx")).isTrue();
        assertThat(documentParserService.isSupported("txt")).isTrue();
        assertThat(documentParserService.isSupported("md")).isTrue();
        assertThat(documentParserService.isSupported("markdown")).isTrue();
        assertThat(documentParserService.isSupported("exe")).isFalse();
    }

    @Test
    void parsesPlainTextStream() {
        String content = "Hello JMindOps Enterprise Knowledge Base!\n\nThis is a test paragraph.";
        ByteArrayInputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        DocumentParserService.ParsedDocument doc = documentParserService.parse(is, "test.txt", "txt");
        assertThat(doc).isNotNull();
        assertThat(doc.getContent()).contains("Hello JMindOps Enterprise Knowledge Base!");
        assertThat(doc.getContent()).contains("This is a test paragraph.");
        assertThat(doc.getFileType()).isEqualTo("txt");
    }

    @Test
    void parsesHtmlStream() {
        String html = "<html><head><title>Test Doc</title></head><body><h1>Heading 1</h1><p>Paragraph content</p></body></html>";
        ByteArrayInputStream is = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));

        DocumentParserService.ParsedDocument doc = documentParserService.parse(is, "sample.html", "html");
        assertThat(doc).isNotNull();
        assertThat(doc.getContent()).contains("Heading 1");
        assertThat(doc.getContent()).contains("Paragraph content");
    }
}
