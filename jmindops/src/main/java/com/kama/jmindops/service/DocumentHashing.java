package com.kama.jmindops.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class DocumentHashing {
    private DocumentHashing() {}

    public static String sha256(InputStream input) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(String value) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sourceKey(String filename) {
        if (filename == null) {
            return "";
        }
        return filename.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }
}
