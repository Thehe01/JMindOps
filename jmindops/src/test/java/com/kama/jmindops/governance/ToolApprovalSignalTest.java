package com.kama.jmindops.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolApprovalSignalTest {

    @Test
    void recognizesRawAndSpringAiJsonStringResponses() {
        String raw = ToolApprovalSignal.waitingMessage("approval-1");

        assertThat(ToolApprovalSignal.isWaitingResponse(raw)).isTrue();
        assertThat(ToolApprovalSignal.isWaitingResponse("\"" + raw + "\"")).isTrue();
        assertThat(ToolApprovalSignal.isWaitingResponse("操作成功")).isFalse();
        assertThat(ToolApprovalSignal.isWaitingResponse(null)).isFalse();
    }

    @Test
    void exposesDeterministicUserFacingWaitingMessage() {
        assertThat(ToolApprovalSignal.userFacingWaitingMessage())
                .contains("人工审批")
                .contains("等待审批")
                .contains("尚未执行")
                .contains("不会重试或绕过审批");
    }
}
