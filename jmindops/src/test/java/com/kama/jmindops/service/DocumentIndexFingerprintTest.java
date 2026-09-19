package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIndexFingerprintTest {

    @Test
    void changesWhenEmbeddingOrParsingPipelineChanges() {
        String baseline = new DocumentIndexFingerprint(
                "bge-m3", 1024, "none", "pipeline-v1").current();

        assertThat(new DocumentIndexFingerprint(
                "other-model", 1024, "none", "pipeline-v1").current())
                .isNotEqualTo(baseline);
        assertThat(new DocumentIndexFingerprint(
                "bge-m3", 1024, "none", "pipeline-v2").current())
                .isNotEqualTo(baseline);
        assertThat(new DocumentIndexFingerprint(
                " BGE-M3 ", 1024, "NONE", "PIPELINE-V1").current())
                .isEqualTo(baseline);
    }
}
