package org.example.service;

import java.util.Map;

public record ChunkDraft(
        String content,
        String title,
        int chunkIndex,
        Map<String, Object> sourceMetadata
) {}
