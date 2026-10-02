package com.kama.jmindops.ai;

/** Application message envelope. Its persisted shape is independent of the model SDK. */
public interface Message {
    String getText();
}
