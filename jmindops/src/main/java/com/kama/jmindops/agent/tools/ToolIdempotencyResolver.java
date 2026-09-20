package com.kama.jmindops.agent.tools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;

@Component
public class ToolIdempotencyResolver {

    private static final Set<String> BUILTIN_IDEMPOTENT_TOOLS = Set.of(
            "knowledgetool",
            "directanswertool",
            "terminate",
            "citytool",
            "datetool",
            "weathertool",
            "databasequery",
            "readfile",
            "listdirectory",
            "checkfileexists",
            "getfileinfo"
    );

    public boolean isIdempotent(String toolName, ToolCallback callback) {
        if (callback != null) {
            IdempotentTool annotation = findAnnotation(callback);
            if (annotation != null) {
                return annotation.value();
            }
        }
        if (toolName != null) {
            String normalized = toolName.toLowerCase(Locale.ROOT);
            if (BUILTIN_IDEMPOTENT_TOOLS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsIdempotencyKey(String toolName, ToolCallback callback) {
        if (callback != null) {
            IdempotentTool annotation = findAnnotation(callback);
            if (annotation != null) {
                return annotation.supportsIdempotencyKey();
            }
        }
        return false;
    }

    public boolean isIdempotent(String toolName, Object toolObject, Method method) {
        if (method != null) {
            IdempotentTool annotation = AnnotatedElementUtils.findMergedAnnotation(method, IdempotentTool.class);
            if (annotation != null) {
                return annotation.value();
            }
        }
        if (toolObject != null) {
            IdempotentTool annotation = AnnotatedElementUtils.findMergedAnnotation(toolObject.getClass(), IdempotentTool.class);
            if (annotation != null) {
                return annotation.value();
            }
        }
        if (toolName != null) {
            String normalized = toolName.toLowerCase(Locale.ROOT);
            if (BUILTIN_IDEMPOTENT_TOOLS.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private IdempotentTool findAnnotation(ToolCallback callback) {
        ToolCallback actual = callback;
        // Unwrap ToolGovernanceCallback or other wrappers if present
        while (actual != null && actual.getClass().getSimpleName().contains("ToolGovernanceCallback")) {
            try {
                java.lang.reflect.Field field = actual.getClass().getDeclaredField("delegate");
                field.setAccessible(true);
                actual = (ToolCallback) field.get(actual);
            } catch (Exception ignored) {
                break;
            }
        }
        if (actual == null) {
            return null;
        }
        try {
            Method method = null;
            Object target = null;
            try {
                Method getMethod = actual.getClass().getMethod("getToolMethod");
                method = (Method) getMethod.invoke(actual);
            } catch (NoSuchMethodException e) {
                try {
                    Method getMethod = actual.getClass().getMethod("getMethod");
                    method = (Method) getMethod.invoke(actual);
                } catch (NoSuchMethodException ignored) {}
            }
            try {
                Method getTarget = actual.getClass().getMethod("getTarget");
                target = getTarget.invoke(actual);
            } catch (NoSuchMethodException ignored) {}

            if (method != null) {
                IdempotentTool annotation = AnnotatedElementUtils.findMergedAnnotation(method, IdempotentTool.class);
                if (annotation != null) {
                    return annotation;
                }
            }
            if (target != null) {
                IdempotentTool annotation = AnnotatedElementUtils.findMergedAnnotation(target.getClass(), IdempotentTool.class);
                if (annotation != null) {
                    return annotation;
                }
            }
            return AnnotatedElementUtils.findMergedAnnotation(actual.getClass(), IdempotentTool.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
