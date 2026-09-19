package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentHashingTest {
    @Test
    void hashesStreamsAndTextWithTheSameSha256() throws Exception {
        byte[] content = "JMindOps 增量索引".getBytes(StandardCharsets.UTF_8);

        assertThat(DocumentHashing.sha256(new ByteArrayInputStream(content)))
                .isEqualTo(DocumentHashing.sha256(new String(content, StandardCharsets.UTF_8)))
                .hasSize(64);
    }

    @Test
    void normalizesFilenameAsStableSourceKey() {
        assertThat(DocumentHashing.sourceKey("  My   Notes.MD "))
                .isEqualTo("my notes.md");
    }
}
