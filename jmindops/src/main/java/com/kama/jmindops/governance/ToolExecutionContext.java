package com.kama.jmindops.governance;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ToolExecutionContext {
    private static final ThreadLocal<ExecutionScope> SCOPE = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void setSessionId(String sessionId) {
        set(sessionId, Collections.emptySet());
    }

    public static void set(String sessionId, Collection<String> allowedKnowledgeBaseIds) {
        Set<String> immutableAllowedIds = allowedKnowledgeBaseIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(allowedKnowledgeBaseIds));
        SCOPE.set(new ExecutionScope(sessionId, immutableAllowedIds));
    }

    public static String getSessionId() {
        ExecutionScope scope = SCOPE.get();
        return scope == null ? null : scope.sessionId();
    }

    /**
     * 工具参数由模型生成，必须在执行边界按当前 Agent 的服务端配置再次校验。
     * 没有执行上下文时一律拒绝，避免工具被绕过 Agent 直接调用。
     */
    public static boolean isKnowledgeBaseAllowed(String knowledgeBaseId) {
        ExecutionScope scope = SCOPE.get();
        return scope != null && scope.allowedKnowledgeBaseIds().contains(knowledgeBaseId);
    }

    public static void clear() {
        SCOPE.remove();
    }

    private record ExecutionScope(String sessionId, Set<String> allowedKnowledgeBaseIds) {
    }
}
