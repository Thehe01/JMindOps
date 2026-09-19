package com.kama.jmindops.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A single catalog for externally supplied MCP callbacks. */
@Component
public class ExternalToolRegistry {
    private final List<ToolCallbackProvider> providers;

    public ExternalToolRegistry(ObjectProvider<ToolCallbackProvider> providers) {
        this.providers = providers.stream().toList();
    }

    public List<ToolCallbackProvider> providers() {
        return providers;
    }

    public List<ToolCallback> callbacks() {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (ToolCallbackProvider provider : providers) {
            ToolCallback[] discovered = provider.getToolCallbacks();
            if (discovered == null) {
                continue;
            }
            Arrays.stream(discovered)
                    .filter(callback -> callback != null && callback.getToolDefinition() != null)
                    .forEach(callback -> callbacks.putIfAbsent(
                            callback.getToolDefinition().name(), callback));
        }
        return List.copyOf(callbacks.values());
    }

    public Set<String> names() {
        return callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toUnmodifiableSet());
    }
}
