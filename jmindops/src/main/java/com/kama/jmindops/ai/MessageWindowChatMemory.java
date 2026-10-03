package com.kama.jmindops.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-runtime memory with LangChain4j's tool/result-aware window eviction. */
public final class MessageWindowChatMemory implements ChatMemory {
    private final int maxMessages;
    private final Map<String, dev.langchain4j.memory.chat.MessageWindowChatMemory> sessions = new ConcurrentHashMap<>();
    private MessageWindowChatMemory(int maxMessages) {
        if (maxMessages < 1) throw new IllegalArgumentException("maxMessages must be positive");
        this.maxMessages = maxMessages;
    }
    private dev.langchain4j.memory.chat.MessageWindowChatMemory memory(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                .id(id).maxMessages(maxMessages).alwaysKeepSystemMessageFirst(true).build());
    }
    @Override public void add(String sessionId, Message message) {
        LangChain4jMessages.toNative(List.of(message)).forEach(memory(sessionId)::add);
    }
    @Override public List<Message> get(String sessionId) { return LangChain4jMessages.fromNative(memory(sessionId).messages()); }
    @Override public void clear(String sessionId) { sessions.remove(sessionId); }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private int maxMessages = 20;
        public Builder maxMessages(int maxMessages) { this.maxMessages = maxMessages; return this; }
        public MessageWindowChatMemory build() { return new MessageWindowChatMemory(maxMessages); }
    }
}
