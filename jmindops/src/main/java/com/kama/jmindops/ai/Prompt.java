package com.kama.jmindops.ai;

import java.util.List;

public final class Prompt {
    private final List<Message> messages;
    private final ToolCallingChatOptions options;
    private Prompt(List<Message> messages, ToolCallingChatOptions options) {
        this.messages = List.copyOf(messages);
        this.options = options;
    }
    public List<Message> getInstructions() { return messages; }
    public ToolCallingChatOptions getOptions() { return options; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private List<Message> messages = List.of();
        private ToolCallingChatOptions options;
        public Builder messages(List<Message> messages) { this.messages = messages; return this; }
        public Builder chatOptions(ToolCallingChatOptions options) { this.options = options; return this; }
        public Prompt build() { return new Prompt(messages, options); }
    }
}
