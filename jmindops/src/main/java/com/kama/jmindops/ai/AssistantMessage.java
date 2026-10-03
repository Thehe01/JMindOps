package com.kama.jmindops.ai;

import java.util.List;

/** Tool-call fields deliberately retain the existing database/checkpoint JSON format. */
public final class AssistantMessage implements Message {
    private final String text;
    private final List<ToolCall> toolCalls;

    public AssistantMessage(String text) { this(text, List.of()); }
    public AssistantMessage(String text, List<ToolCall> toolCalls) {
        this.text = text == null ? "" : text;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
    @Override public String getText() { return text; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public record ToolCall(String id, String type, String name, String arguments) {}

    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String content;
        private List<ToolCall> toolCalls;
        public Builder content(String content) { this.content = content; return this; }
        public Builder toolCalls(List<ToolCall> calls) { this.toolCalls = calls; return this; }
        public AssistantMessage build() { return new AssistantMessage(content, toolCalls); }
    }
}
