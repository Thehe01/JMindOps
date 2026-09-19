package com.kama.jmindops.model.vo;

import com.kama.jmindops.agent.tools.Tool;
import com.kama.jmindops.agent.tools.ToolType;
import org.springframework.ai.tool.ToolCallback;

public record ToolVO(String name, String description, ToolType type) {

    public static ToolVO from(Tool tool) {
        return new ToolVO(tool.getName(), tool.getDescription(), tool.getType());
    }

    public static ToolVO from(ToolCallback callback) {
        return new ToolVO(
                callback.getToolDefinition().name(),
                callback.getToolDefinition().description(),
                ToolType.OPTIONAL
        );
    }
}
