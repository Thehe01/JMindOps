package com.kama.jmindops.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Identifies every setting that determines the meaning of a persisted vector. */
@Component
public class DocumentIndexFingerprint {
    private final String value;

    public DocumentIndexFingerprint(
            @Value("${rag.embedding.model}") String embeddingModel,
            @Value("${rag.embedding.dimensions:1024}") int embeddingDimensions,
            @Value("${rag.embedding.normalization:none}") String embeddingNormalization,
            @Value("${rag.index.pipeline-version:tika-flexmark-chunk-v1}") String pipelineVersion
    ) {
        String identity = String.join("\n",
                "model=" + normalize(embeddingModel),
                "dimensions=" + embeddingDimensions,
                "normalization=" + normalize(embeddingNormalization),
                "pipeline=" + normalize(pipelineVersion));
        this.value = DocumentHashing.sha256(identity);
    }

    public String current() {
        return value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
