package org.example.service;

import java.util.List;

public record ChunkDraft(
        String content,
        String title,
        int chunkIndex,
        String contentType,
        List<String> headingPath,
        String retrievalText
) {
    public ChunkDraft {
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }
}
