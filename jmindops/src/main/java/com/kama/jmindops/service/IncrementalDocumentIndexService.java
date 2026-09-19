package com.kama.jmindops.service;

import com.kama.jmindops.mapper.ChunkBgeM3Mapper;
import com.kama.jmindops.model.entity.ChunkBgeM3;
import com.kama.jmindops.model.entity.Document;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IncrementalDocumentIndexService {
    private final ChunkBgeM3Mapper chunkMapper;
    private final RagService ragService;
    private final DocumentIndexStore indexStore;
    private final DocumentIndexFingerprint indexFingerprint;

    @Autowired
    public IncrementalDocumentIndexService(
            ChunkBgeM3Mapper chunkMapper,
            RagService ragService,
            DocumentIndexStore indexStore,
            DocumentIndexFingerprint indexFingerprint
    ) {
        this.chunkMapper = chunkMapper;
        this.ragService = ragService;
        this.indexStore = indexStore;
        this.indexFingerprint = indexFingerprint;
    }

    public String currentFingerprint() {
        return indexFingerprint.current();
    }

    public IndexResult replaceIndex(Document document, boolean newDocument, List<ChunkInput> inputs) {
        String activeFingerprint = currentFingerprint();
        document.setIndexFingerprint(activeFingerprint);
        List<ChunkBgeM3> existing = newDocument
                ? List.of()
                : chunkMapper.selectByDocumentId(document.getId());
        Map<String, Deque<ChunkBgeM3>> reusable = new HashMap<>();
        for (ChunkBgeM3 chunk : existing) {
            if (chunk.getChunkHash() != null
                    && chunk.getEmbedding() != null
                    && activeFingerprint.equals(chunk.getIndexFingerprint())) {
                reusable.computeIfAbsent(chunk.getChunkHash(), ignored -> new ArrayDeque<>()).add(chunk);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        List<ChunkBgeM3> replacement = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int reusedCount = 0;
        int embeddedCount = 0;

        for (int index = 0; index < inputs.size(); index++) {
            ChunkInput input = inputs.get(index);
            String hash = DocumentHashing.sha256(input.content());
            int occurrence = occurrences.merge(hash, 1, Integer::sum) - 1;
            ChunkBgeM3 old = pollSameContent(reusable.get(hash), input.content());
            float[] embedding;
            String chunkId;
            LocalDateTime createdAt;
            if (old != null) {
                embedding = old.getEmbedding();
                chunkId = old.getId();
                createdAt = old.getCreatedAt();
                reusedCount++;
            } else {
                embedding = ragService.embed(input.content());
                chunkId = stableChunkId(document.getId(), hash, occurrence);
                createdAt = now;
                embeddedCount++;
            }
            replacement.add(ChunkBgeM3.builder()
                    .id(chunkId)
                    .kbId(document.getKbId())
                    .docId(document.getId())
                    .content(input.content())
                    .chunkHash(hash)
                    .indexFingerprint(activeFingerprint)
                    .chunkIndex(index)
                    .documentVersion(document.getIndexVersion())
                    .metadata(input.metadata())
                    .embedding(embedding)
                    .createdAt(createdAt == null ? now : createdAt)
                    .updatedAt(now)
                    .build());
        }

        document.setChunkCount(replacement.size());
        document.setIndexStatus("READY");
        document.setIndexedAt(now);
        document.setUpdatedAt(now);
        indexStore.replace(document, newDocument, replacement);
        return new IndexResult(replacement.size(), reusedCount, embeddedCount);
    }

    private ChunkBgeM3 pollSameContent(Deque<ChunkBgeM3> candidates, String content) {
        if (candidates == null) {
            return null;
        }
        while (!candidates.isEmpty()) {
            ChunkBgeM3 candidate = candidates.removeFirst();
            if (content.equals(candidate.getContent())) {
                return candidate;
            }
        }
        return null;
    }

    private String stableChunkId(String documentId, String hash, int occurrence) {
        return UUID.nameUUIDFromBytes(
                (documentId + ':' + hash + ':' + occurrence).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public record ChunkInput(String content, String metadata) {}

    public record IndexResult(int chunkCount, int reusedChunkCount, int embeddedChunkCount) {}
}
