package com.kama.jmindops.exception;

/**
 * 任务已取消异常：任务在执行期间已被用户或上层逻辑取消
 */
public class TaskCancelledException extends RuntimeException {
    public TaskCancelledException(String message) {
        super(message);
    }

    public TaskCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
