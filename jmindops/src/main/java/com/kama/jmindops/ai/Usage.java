package com.kama.jmindops.ai;

public record Usage(Integer promptTokens, Integer generationTokens, Integer totalTokens) {
    public Integer getPromptTokens() { return promptTokens; }
    public Integer getGenerationTokens() { return generationTokens; }
    public Integer getTotalTokens() { return totalTokens; }
}
