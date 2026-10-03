package com.kama.jmindops.ai;

public record Generation(AssistantMessage output) {
    public AssistantMessage getOutput() { return output; }
}
