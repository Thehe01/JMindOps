package com.kama.jmindops.exception;

/**
 * 任务租约失效异常：当前 Worker 已失去对该任务的有效租约（已被 recovery 或其他 Worker 接管）
 */
public class StaleDocumentIndexLeaseException extends RuntimeException {
    public StaleDocumentIndexLeaseException(String message) {
        super(message);
    }

    public StaleDocumentIndexLeaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
