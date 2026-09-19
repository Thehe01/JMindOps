package com.kama.jmindops.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationRequestFingerprintTest {
    @Test
    void isDeterministicAndIncludesAllRequestFields() {
        String first = GenerationRequestFingerprint.create("agent-a", "session-a", "hello");

        assertThat(first).hasSize(64);
        assertThat(GenerationRequestFingerprint.create("agent-a", "session-a", "hello")).isEqualTo(first);
        assertThat(GenerationRequestFingerprint.create("agent-b", "session-a", "hello")).isNotEqualTo(first);
        assertThat(GenerationRequestFingerprint.create("agent-a", "session-b", "hello")).isNotEqualTo(first);
        assertThat(GenerationRequestFingerprint.create("agent-a", "session-a", "world")).isNotEqualTo(first);
    }
}

