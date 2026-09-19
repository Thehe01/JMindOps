package com.kama.jmindops.exception;

/**
 * 智能体生成任务租约失效异常：当前 Worker 已失去对该 Generation 的有效租约（已被其他 Worker 接管或 fencing 拒绝）
 */
public class StaleGenerationLeaseException extends RuntimeException {
    public StaleGenerationLeaseException(String message) {
        super(message);
    }

    public StaleGenerationLeaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
