package com.kama.jmindops.validation;

public final class ValidationPatterns {
    public static final String UUID =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";
    public static final String USERNAME = "^[A-Za-z0-9_-]+$";
    public static final String TOOL_NAME = "^[A-Za-z0-9_.:-]+$";

    private ValidationPatterns() {
    }
}
