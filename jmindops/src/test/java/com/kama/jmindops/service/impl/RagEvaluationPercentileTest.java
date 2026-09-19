package com.kama.jmindops.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvaluationPercentileTest {
    @Test
    void usesNearestRankForLatencyPercentiles() {
        List<Long> samples = List.of(90L, 10L, 50L, 20L, 40L);

        assertThat(RagServiceImpl.percentile(samples, 0.50)).isEqualTo(40L);
        assertThat(RagServiceImpl.percentile(samples, 0.95)).isEqualTo(90L);
        assertThat(RagServiceImpl.percentile(List.of(), 0.95)).isZero();
    }
}

