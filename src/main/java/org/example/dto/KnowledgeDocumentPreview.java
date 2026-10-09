package org.example.dto;

/**
 * 知识库文档预览。TXT / Markdown 为原文，其他格式为解析后的 Markdown。
 */
public record KnowledgeDocumentPreview(
        String id,
        String fileName,
        String status,
        boolean previewAvailable,
        String previewKind,
        String content,
        String message
) {
    public static KnowledgeDocumentPreview original(String id, String fileName, String status, String content) {
        return new KnowledgeDocumentPreview(id, fileName, status, true, "original", content, null);
    }

    public static KnowledgeDocumentPreview parsed(String id, String fileName, String status, String content) {
        return new KnowledgeDocumentPreview(id, fileName, status, true, "parsed", content, null);
    }

    public static KnowledgeDocumentPreview unavailable(String id, String fileName, String status) {
        return new KnowledgeDocumentPreview(id, fileName, status, false, "none", null, "暂时无法预览");
    }
}
