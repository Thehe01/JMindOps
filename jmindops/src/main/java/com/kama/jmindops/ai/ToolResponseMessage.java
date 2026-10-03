package com.kama.jmindops.ai;

import java.util.List;

/** Batch envelope used by the ledger, SSE events and checkpoints. */
public final class ToolResponseMessage implements Message {
    private final List<ToolResponse> responses;
    private ToolResponseMessage(List<ToolResponse> responses) { this.responses = List.copyOf(responses); }
    @Override public String getText() { return ""; }
    public List<ToolResponse> getResponses() { return responses; }
    public record ToolResponse(String id, String name, String responseData) {}
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private List<ToolResponse> responses = List.of();
        public Builder responses(List<ToolResponse> responses) { this.responses = responses; return this; }
        public ToolResponseMessage build() { return new ToolResponseMessage(responses); }
    }
}
