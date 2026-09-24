package org.example.dto;

/**
 * 知识库文档摘要，供前端列表展示。
 */
public record KnowledgeDocumentSummary(
        String fileName,
        String extension,
        int chunkCount,
        Long fileSizeBytes,
        String indexedAt
) {
}
