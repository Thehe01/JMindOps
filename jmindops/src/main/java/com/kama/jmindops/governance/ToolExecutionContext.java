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
        set(sessionId, null, Collections.emptySet());
    }

    public static void set(String sessionId, Collection<String> allowedKnowledgeBaseIds) {
        set(sessionId, null, allowedKnowledgeBaseIds);
    }

    public static void set(String sessionId, String generationId, Collection<String> allowedKnowledgeBaseIds) {
        set(sessionId, generationId, allowedKnowledgeBaseIds, null);
    }

    public static void set(String sessionId, String generationId, Collection<String> allowedKnowledgeBaseIds, String toolCallId) {
        Set<String> immutableAllowedIds = allowedKnowledgeBaseIds == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(allowedKnowledgeBaseIds));
        SCOPE.set(new ExecutionScope(sessionId, generationId, immutableAllowedIds, toolCallId));
    }

    public static void setToolCallId(String toolCallId) {
        ExecutionScope scope = SCOPE.get();
        if (scope != null) {
            SCOPE.set(new ExecutionScope(scope.sessionId(), scope.generationId(), scope.allowedKnowledgeBaseIds(), toolCallId));
        }
    }

    public static String getSessionId() {
        ExecutionScope scope = SCOPE.get();
        return scope == null ? null : scope.sessionId();
    }

    public static String getGenerationId() {
        ExecutionScope scope = SCOPE.get();
        return scope == null ? null : scope.generationId();
    }

    public static String getToolCallId() {
        ExecutionScope scope = SCOPE.get();
        return scope == null ? null : scope.toolCallId();
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

    private record ExecutionScope(
            String sessionId,
            String generationId,
            Set<String> allowedKnowledgeBaseIds,
            String toolCallId
    ) {
    }
}
