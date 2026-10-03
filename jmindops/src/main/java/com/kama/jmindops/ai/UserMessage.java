package com.kama.jmindops.ai;

public record UserMessage(String text) implements Message {
    @Override public String getText() { return text; }
}
