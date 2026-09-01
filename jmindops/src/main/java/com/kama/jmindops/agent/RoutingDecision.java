package com.kama.jmindops.agent;

public enum RoutingDecision {
    WEATHER("天气专家", "专精于查询各地天气信息的助手。"),
    RAG("知识库专家", "专精于从本地文档知识库中搜索答案的助手。"),
    MCP("外部系统操作专家", "专精于使用 MCP (Model Context Protocol) 协议调用外部系统（如读取数据库、操作文件等）的助手。"),
    CHAT("闲聊助理", "一个乐于助人的日常闲聊助手。不需要调用任何外部工具。");

    private final String roleName;
    private final String description;

    RoutingDecision(String roleName, String description) {
        this.roleName = roleName;
        this.description = description;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getDescription() {
        return description;
    }
}
