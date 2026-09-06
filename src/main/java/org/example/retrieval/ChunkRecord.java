package org.example.retrieval;

import java.util.List;
import java.util.UUID;

public record ChunkRecord(
        UUID id,
        String fileName,
        String title,
        String content,
        List<String> tokens,
        List<Float> embedding,
        int chunkIndex,
        String metadataJson
) {}
