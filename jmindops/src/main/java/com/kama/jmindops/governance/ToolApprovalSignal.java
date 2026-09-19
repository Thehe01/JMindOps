package com.kama.jmindops.governance;

/** Shared wire marker for approval-gated tool results. */
public final class ToolApprovalSignal {
    private static final String PREFIX = "操作需要人工审批，审批编号：";
    private static final String USER_FACING_WAITING_MESSAGE =
            "该操作已提交人工审批，当前正在等待审批，尚未执行。我不会重试或绕过审批；批准后请确认继续。";

    private ToolApprovalSignal() {
    }

    public static String waitingMessage(String approvalId) {
        return PREFIX + approvalId + "。请在界面批准后让用户确认继续。";
    }

    public static boolean isWaitingResponse(String responseData) {
        if (responseData == null) {
            return false;
        }
        // Spring AI stores a String tool result as JSON text in ToolResponseMessage.
        // Direct calls/tests may still provide the unquoted representation.
        return responseData.startsWith(PREFIX) || responseData.startsWith("\"" + PREFIX);
    }

    public static String userFacingWaitingMessage() {
        return USER_FACING_WAITING_MESSAGE;
    }
}
