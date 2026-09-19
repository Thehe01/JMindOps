package com.kama.jmindops.governance;

/** Prevents the method-level fallback aspect from governing a callback twice. */
final class ToolGovernanceInvocationContext {
    private static final ThreadLocal<Boolean> CALLBACK_GOVERNED =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ToolGovernanceInvocationContext() {
    }

    static boolean isCallbackGoverned() {
        return CALLBACK_GOVERNED.get();
    }

    static void enterCallback() {
        CALLBACK_GOVERNED.set(Boolean.TRUE);
    }

    static void exitCallback() {
        CALLBACK_GOVERNED.remove();
    }
}
