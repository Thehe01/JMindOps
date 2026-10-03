package com.kama.jmindops.ai;

import java.util.List;
import java.util.Map;

public final class AssistantMessageFactory {
    private AssistantMessageFactory() {}
    public static AssistantMessage create(String text, Map<String, Object> metadata,
                                           List<AssistantMessage.ToolCall> calls) {
        return new AssistantMessage(text, calls);
    }
}
