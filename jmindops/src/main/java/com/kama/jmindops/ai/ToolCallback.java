package com.kama.jmindops.ai;

/** Application execution boundary: policy, HITL and ledger always run before call(). */
public interface ToolCallback {
    ToolDefinition getToolDefinition();
    String call(String arguments);
    default String call(String arguments, ToolContext context) { return call(arguments); }
}
