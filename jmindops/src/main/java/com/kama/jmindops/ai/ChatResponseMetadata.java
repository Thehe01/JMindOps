package com.kama.jmindops.ai;

public record ChatResponseMetadata(Usage usage, String model) {
    public Usage getUsage() { return usage; }
    public String getModel() { return model; }
}
