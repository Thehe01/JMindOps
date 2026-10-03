package com.kama.jmindops.ai;

import java.util.List;

public final class ChatResponse {
    private final List<Generation> generations;
    private final ChatResponseMetadata metadata;
    public ChatResponse(List<Generation> generations) { this(generations, null); }
    public ChatResponse(List<Generation> generations, ChatResponseMetadata metadata) {
        this.generations = List.copyOf(generations);
        this.metadata = metadata;
    }
    public Generation getResult() { return generations.isEmpty() ? null : generations.get(0); }
    public ChatResponseMetadata getMetadata() { return metadata; }
    public boolean hasToolCalls() { return getResult() != null && !getResult().getOutput().getToolCalls().isEmpty(); }
}
