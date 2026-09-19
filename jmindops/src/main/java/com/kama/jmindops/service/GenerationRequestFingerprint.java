package com.kama.jmindops.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class GenerationRequestFingerprint {
    private GenerationRequestFingerprint() {
    }

    public static String create(String agentId, String sessionId, String content) {
        String canonical = agentId + "\n" + sessionId + "\n" + content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}

