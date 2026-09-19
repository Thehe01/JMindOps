package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class IndexRetryPolicyTest {

    @Test
    void calculatesExponentialBackoffCorrectly() {
        IndexRetryPolicy policy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.calculateBackoff(3)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    void capsBackoffAtMaxBackoff() {
        IndexRetryPolicy policy = new IndexRetryPolicy(5, 10, 2.0, 30, 120);

        assertThat(policy.calculateBackoff(0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.calculateBackoff(1)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.calculateBackoff(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.calculateBackoff(10)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void calculatesNextRetryAtInFuture() {
        IndexRetryPolicy policy = new IndexRetryPolicy(3, 5, 2.0, 60, 120);
        LocalDateTime before = LocalDateTime.now();
        LocalDateTime next = policy.calculateNextRetryAt(0);

        assertThat(next).isAfter(before);
        assertThat(Duration.between(before, next).getSeconds()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void distinguishesRetryableFromFatalExceptions() {
        IndexRetryPolicy policy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        assertThat(policy.isRetryable(new IOException("Connection timed out"))).isTrue();
        assertThat(policy.isRetryable(new RuntimeException("Embedding server unavailable"))).isTrue();

        assertThat(policy.isRetryable(new BizException("文档中没有可索引的文本内容"))).isFalse();
        assertThat(policy.isRetryable(new BizException("不支持的文件格式"))).isFalse();
        assertThat(policy.isRetryable(new BizException("文档已被其他请求更新，请重新上传最新版本"))).isFalse();
        assertThat(policy.isRetryable(new IllegalArgumentException("invalid argument"))).isFalse();
    }

    @Test
    void exhaustsRetriesWhenLimitReached() {
        IndexRetryPolicy policy = new IndexRetryPolicy(3, 2, 2.0, 60, 120);

        assertThat(policy.canRetry(0, new IOException())).isTrue();
        assertThat(policy.canRetry(1, new IOException())).isTrue();
        assertThat(policy.canRetry(2, new IOException())).isTrue();
        assertThat(policy.canRetry(3, new IOException())).isFalse();
        assertThat(policy.canRetry(4, new IOException())).isFalse();
    }
}
