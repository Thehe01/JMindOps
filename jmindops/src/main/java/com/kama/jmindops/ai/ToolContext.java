package com.kama.jmindops.ai;

import java.util.Map;

/** Runtime context supplied only when the governed execute phase invokes a tool. */
public record ToolContext(Map<String, Object> context) {
    public ToolContext { context = Map.copyOf(context); }
    public Map<String, Object> getContext() { return context; }
}
