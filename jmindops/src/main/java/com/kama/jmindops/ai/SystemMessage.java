package com.kama.jmindops.ai;

public record SystemMessage(String text) implements Message {
    @Override public String getText() { return text; }
}
