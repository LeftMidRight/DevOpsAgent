package org.example.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * knowledge_documents 行记录。
 */
public record KnowledgeDocumentRow(
        UUID id,
        String fileName,
        String objectKey,
        long sizeBytes,
        String status,
        int chunkCount,
        String errorMessage,
        String mediaType,
        String parserName,
        String parserVersion,
        String parsedObjectKey,
        Instant parsedAt,
        Instant createdAt,
        Instant indexedAt
) {}
