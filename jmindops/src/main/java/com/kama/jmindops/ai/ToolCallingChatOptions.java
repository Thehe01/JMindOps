package com.kama.jmindops.ai;

import java.util.List;

/** Request settings only. Model invocation never executes callbacks. */
public final class ToolCallingChatOptions {
    private Double temperature;
    private Double topP;
    private List<ToolCallback> callbacks = List.of();
    public Double getTemperature() { return temperature; }
    public Double getTopP() { return topP; }
    public List<ToolCallback> getToolCallbacks() { return callbacks; }
    public void setToolCallbacks(List<ToolCallback> callbacks) { this.callbacks = List.copyOf(callbacks); }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private final ToolCallingChatOptions options = new ToolCallingChatOptions();
        public Builder temperature(Double temperature) { options.temperature = temperature; return this; }
        public Builder topP(Double topP) { options.topP = topP; return this; }
        public ToolCallingChatOptions build() { return options; }
    }
}
