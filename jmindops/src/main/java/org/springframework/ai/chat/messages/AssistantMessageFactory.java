package org.springframework.ai.chat.messages;

import java.util.List;
import java.util.Map;

public class AssistantMessageFactory {
    public static AssistantMessage create(String text, Map<String, Object> metadata, List<AssistantMessage.ToolCall> toolCalls) {
        try {
            java.lang.reflect.Constructor<AssistantMessage> constructor = AssistantMessage.class.getDeclaredConstructor(
                    String.class, Map.class, List.class, List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(text, metadata, toolCalls, List.of());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create AssistantMessage via reflection", e);
        }
    }
}
