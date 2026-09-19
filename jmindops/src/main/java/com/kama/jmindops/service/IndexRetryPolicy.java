package com.kama.jmindops.service;

import com.kama.jmindops.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 负责可重试判断与指数退避策略计算
 */
@Component
public class IndexRetryPolicy {
    private final int maxRetries;
    private final Duration initialBackoff;
    private final double backoffMultiplier;
    private final Duration maxBackoff;
    private final Duration runningTimeout;

    public IndexRetryPolicy(
            @Value("${app.document-index.max-retries:3}") int maxRetries,
            @Value("${app.document-index.initial-backoff-seconds:2}") long initialBackoffSeconds,
            @Value("${app.document-index.backoff-multiplier:2.0}") double backoffMultiplier,
            @Value("${app.document-index.max-backoff-seconds:60}") long maxBackoffSeconds,
            @Value("${app.document-index.running-timeout-seconds:120}") long runningTimeoutSeconds
    ) {
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoff = Duration.ofSeconds(Math.max(1, initialBackoffSeconds));
        this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
        this.maxBackoff = Duration.ofSeconds(Math.max(1, maxBackoffSeconds));
        this.runningTimeout = Duration.ofSeconds(Math.max(10, runningTimeoutSeconds));
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public Duration getRunningTimeout() {
        return runningTimeout;
    }

    public boolean canRetry(int retryCount, Throwable throwable) {
        return canRetry(retryCount, this.maxRetries, throwable);
    }

    public boolean canRetry(int retryCount, int maxRetries, Throwable throwable) {
        if (retryCount >= maxRetries) {
            return false;
        }
        return isRetryable(throwable);
    }

    public boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return true;
        }
        if (throwable instanceof BizException bizException) {
            String msg = bizException.getMessage();
            if (msg != null && (
                    msg.contains("没有可索引的文本内容") ||
                    msg.contains("不支持的文件格式") ||
                    msg.contains("已被其他请求更新") ||
                    msg.contains("文件为空") ||
                    msg.contains("包含二进制内容") ||
                    msg.contains("伪造的 Content-Type")
            )) {
                return false;
            }
        }
        if (throwable instanceof IllegalArgumentException) {
            return false;
        }
        return true;
    }

    public Duration calculateBackoff(int retryCount) {
        if (retryCount < 0) {
            return initialBackoff;
        }
        double factor = Math.pow(backoffMultiplier, retryCount);
        long millis = (long) (initialBackoff.toMillis() * factor);
        if (millis > maxBackoff.toMillis()) {
            return maxBackoff;
        }
        return Duration.ofMillis(millis);
    }

    public LocalDateTime calculateNextRetryAt(int retryCount) {
        return LocalDateTime.now().plus(calculateBackoff(retryCount));
    }
}
