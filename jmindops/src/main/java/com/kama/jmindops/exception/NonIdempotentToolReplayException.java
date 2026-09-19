package com.kama.jmindops.exception;

/**
 * 非幂等工具重放异常：外部非幂等工具处于 UNKNOWN 状态，恢复时禁止自动重试以防止重复副作用，需人工介入对账
 */
public class NonIdempotentToolReplayException extends RuntimeException {
    public NonIdempotentToolReplayException(String message) {
        super(message);
    }

    public NonIdempotentToolReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
