package com.kama.jmindops.agent.tools;

public interface Tool {
    String getName();

    String getDescription();

    ToolType getType();
}
