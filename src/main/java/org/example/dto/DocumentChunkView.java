package org.example.dto;

/**
 * 文档分块预览。不含向量和分词。
 */
public record DocumentChunkView(int chunkIndex, String title, String content) {
}
