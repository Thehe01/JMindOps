package com.kama.jmindops.agent;

public enum AgentState {
    IDLE,  // 空闲
    PLANNING,  // 计划中
    THINKING,  // 思考中
    EXECUTING, // 执行中
    WAITING_APPROVAL, // 等待人工审批 (HITL Suspend)
    FINISHED,  // 正常结束
    ERROR  // 错误结束
}
