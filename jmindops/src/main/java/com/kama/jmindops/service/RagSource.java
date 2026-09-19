package com.kama.jmindops.service;

public record RagSource(String documentId, String sourceKey, String content, Double relevanceScore) {
    public RagSource(String documentId, String content) {
        this(documentId, null, content, null);
    }

    public RagSource(String documentId, String content, Double relevanceScore) {
        this(documentId, null, content, relevanceScore);
    }
}
