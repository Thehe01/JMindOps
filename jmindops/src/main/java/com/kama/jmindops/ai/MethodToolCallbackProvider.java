package com.kama.jmindops.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses LangChain4j schemas and argument parsing while retaining the Spring proxy. */
public final class MethodToolCallbackProvider implements ToolCallbackProvider {
    private final List<Object> objects;
    private MethodToolCallbackProvider(List<Object> objects) { this.objects = objects; }
    @Override public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object object : objects) {
            for (Method method : ReflectionUtils.getAllDeclaredMethods(AopUtils.getTargetClass(object))) {
                if (AnnotatedElementUtils.hasAnnotation(method, dev.langchain4j.agent.tool.Tool.class)) {
                    Method invocationMethod = AopUtils.selectInvocableMethod(method, object.getClass());
                    callbacks.add(new MethodCallback(object, method, invocationMethod));
                }
            }
        }
        return callbacks.toArray(ToolCallback[]::new);
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private List<Object> objects = List.of();
        public Builder toolObjects(Object... objects) { this.objects = List.of(objects); return this; }
        public MethodToolCallbackProvider build() { return new MethodToolCallbackProvider(objects); }
    }
    public static final class MethodCallback implements ToolCallback {
        private final Object toolObject;
        private final Method toolMethod;
        private final ToolDefinition definition;
        private final DefaultToolExecutor executor;
        private MethodCallback(Object object, Method method, Method invocationMethod) {
            this.toolObject = object;
            this.toolMethod = method;
            this.definition = new ToolDefinition(ToolSpecifications.toolSpecificationFrom(method));
            this.executor = DefaultToolExecutor.builder().object(object).originalMethod(method)
                    .methodToInvoke(invocationMethod).propagateToolExecutionExceptions(true).build();
        }
        public Method getToolMethod() { return toolMethod; }
        public Object getToolObject() { return toolObject; }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public String call(String arguments) { return call(arguments, new ToolContext(java.util.Map.of())); }
        @Override public String call(String arguments, ToolContext context) {
            try {
                return executor.executeWithContext(ToolExecutionRequest.builder().name(definition.name()).arguments(arguments).build(),
                        InvocationContext.builder().invocationParameters(InvocationParameters.from(context.getContext())).build())
                        .resultText();
            } catch (ToolExecutionException exception) {
                // Preserve fencing and uncertain-execution exceptions for the application's ledger.
                if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                if (exception.getCause() instanceof Error error) throw error;
                throw exception;
            }
        }
    }
}
