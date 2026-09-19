package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationTaskStoreTest {
    @Test
    void sanitizesSensitiveAndMultilineFailureDetails() {
        String sanitized = GenerationTaskStore.sanitizeError(
                "provider failed\napi_key=sk-secret token:bearer-value");

        assertThat(sanitized).isEqualTo("provider failed api_key=****** token=******");
    }

    @Test
    void boundsPersistedFailureLength() {
        assertThat(GenerationTaskStore.sanitizeError("x".repeat(2_000))).hasSize(1_000);
    }
}
