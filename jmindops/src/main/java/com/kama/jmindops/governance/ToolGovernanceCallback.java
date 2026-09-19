package com.kama.jmindops.governance;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Enforces approval at Spring AI's actual callback boundary. Method-level AOP
 * alone is insufficient because MethodToolCallback reflects a method discovered
 * from the ultimate target class onto a possibly layered proxy.
 */
public final class ToolGovernanceCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolGovernanceService governanceService;
    private final String riskLevel;

    private ToolGovernanceCallback(
            ToolCallback delegate,
            ToolGovernanceService governanceService,
            String riskLevel
    ) {
        this.delegate = delegate;
        this.governanceService = governanceService;
        this.riskLevel = riskLevel;
    }

    public static ToolCallback wrapIfRequired(
            Object toolObject,
            ToolCallback callback,
            ToolGovernanceService governanceService
    ) {
        Objects.requireNonNull(toolObject, "toolObject");
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(governanceService, "governanceService");
        RequiresToolApproval approval = findApprovalAnnotation(
                toolObject, callback.getToolDefinition().name());
        return approval == null
                ? callback
                : new ToolGovernanceCallback(callback, governanceService, approval.riskLevel());
    }

    /** External MCP callbacks have no local annotation, so they are always treated as high risk. */
    public static ToolCallback wrapExternal(
            ToolCallback callback,
            ToolGovernanceService governanceService
    ) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(governanceService, "governanceService");
        return callback instanceof ToolGovernanceCallback
                ? callback
                : new ToolGovernanceCallback(callback, governanceService, "HIGH");
    }

    static RequiresToolApproval findApprovalAnnotation(Object toolObject, String callbackName) {
        Class<?> targetClass = AopUtils.getTargetClass(toolObject);
        return Arrays.stream(ReflectionUtils.getAllDeclaredMethods(targetClass))
                .filter(method -> callbackName.equals(toolName(method)))
                .map(method -> AnnotatedElementUtils.findMergedAnnotation(
                        method, RequiresToolApproval.class))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String toolName(Method method) {
        Tool tool = AnnotatedElementUtils.findMergedAnnotation(method, Tool.class);
        if (tool == null) {
            return "";
        }
        return tool.name().isBlank() ? method.getName() : tool.name();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return govern(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return govern(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String govern(String toolInput, Supplier<String> invocation) {
        String toolName = getToolDefinition().name();
        Object[] arguments = new Object[]{toolInput};
        long startedAt = System.currentTimeMillis();
        ToolGovernanceService.ApprovalDecision decision =
                governanceService.requireApproval(toolName, arguments, riskLevel);
        if (!decision.approved()) {
            String response = ToolApprovalSignal.waitingMessage(decision.approvalId());
            governanceService.audit(toolName, riskLevel, "PENDING_APPROVAL",
                    arguments, response, System.currentTimeMillis() - startedAt);
            return response;
        }

        ToolGovernanceInvocationContext.enterCallback();
        try {
            String result = invocation.get();
            governanceService.audit(toolName, riskLevel, "SUCCEEDED",
                    arguments, result, System.currentTimeMillis() - startedAt);
            return result;
        } catch (RuntimeException | Error exception) {
            governanceService.audit(toolName, riskLevel, "FAILED",
                    arguments, exception.getMessage(), System.currentTimeMillis() - startedAt);
            throw exception;
        } finally {
            ToolGovernanceInvocationContext.exitCallback();
        }
    }
}
