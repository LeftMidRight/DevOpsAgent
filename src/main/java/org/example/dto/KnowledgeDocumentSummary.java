package org.example.dto;

import org.example.repository.KnowledgeDocumentRow;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 知识库文档摘要，供前端列表与上传/分块响应展示。
 */
public record KnowledgeDocumentSummary(
        String id,
        String fileName,
        String extension,
        int chunkCount,
        Long fileSizeBytes,
        String indexedAt,
        String status,
        String errorMessage
) {

    private static final DateTimeFormatter INDEX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public static KnowledgeDocumentSummary from(KnowledgeDocumentRow row) {
        return new KnowledgeDocumentSummary(
                row.id() == null ? null : row.id().toString(),
                row.fileName(),
                extensionOf(row.fileName()),
                row.chunkCount(),
                row.sizeBytes(),
                row.indexedAt() == null ? "—" : INDEX_TIME_FORMAT.format(row.indexedAt()),
                row.status(),
                row.errorMessage());
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}