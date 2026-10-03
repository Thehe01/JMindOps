package com.kama.jmindops.ai;

import dev.langchain4j.agent.tool.ToolSpecification;

public record ToolDefinition(ToolSpecification specification) {
    public String name() { return specification.name(); }
    public String description() { return specification.description(); }
}
