package org.example.service;

import com.google.gson.Gson;
import org.example.dto.DocumentChunk;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.example.retrieval.ChunkRecord;
import org.example.retrieval.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 阶段二：分块。从对象存储读取文档原文，分片、向量化并写入向量库，
 * 同步更新文档状态（INDEXED / FAILED）。
 */
@Service
public class DocumentChunkingService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkingService.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentRepository documentRepository;
    private final ObjectStorageService objectStorageService;
    private final DocumentChunkService chunkService;
    private final VectorEmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final ChineseTokenizer tokenizer = new ChineseTokenizer();
    private final Gson gson = new Gson();

    public DocumentChunkingService(
            DocumentRepository documentRepository,
            ObjectStorageService objectStorageService,
            DocumentChunkService chunkService,
            VectorEmbeddingService embeddingService,
            ChunkRepository chunkRepository) {
        this.documentRepository = documentRepository;
        this.objectStorageService = objectStorageService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 按对象存储中的当前内容重建该文档的全部分片（重复调用幂等）。
     * 分块失败时标记 FAILED 并返回失败摘要，不抛出异常；文档不存在时抛出 IllegalArgumentException。
     */
    public KnowledgeDocumentSummary chunkDocument(UUID documentId) {
        KnowledgeDocumentRow document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + documentId));
        try {
            String content = new String(
                    objectStorageService.getObject(document.objectKey()), StandardCharsets.UTF_8);
            List<DocumentChunk> chunks = chunkService.chunkDocument(content, document.fileName());
            List<ChunkRecord> rows = buildChunkRecords(document, chunks);
            chunkRepository.replaceDocumentChunks(documentId, document.fileName(), rows);
            documentRepository.markIndexed(documentId, rows.size());
            logger.info("文档分块完成 - documentId: {}, fileName: {}, 分片数: {}",
                    documentId, document.fileName(), rows.size());
        } catch (Exception e) {
            documentRepository.markFailed(documentId, truncate(e.getMessage()));
            logger.error("文档分块失败 - documentId: {}, fileName: {}", documentId, document.fileName(), e);
        }
        return loadSummary(documentId);
    }

    private List<ChunkRecord> buildChunkRecords(KnowledgeDocumentRow document, List<DocumentChunk> chunks) {
        List<ChunkRecord> rows = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            List<Float> vector = embeddingService.generateEmbedding(chunk.getContent());
            List<String> tokens = tokenizer.tokenize(chunk.getContent());
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (document.id() + "_" + chunk.getChunkIndex()).getBytes(StandardCharsets.UTF_8));
            rows.add(new ChunkRecord(
                    chunkId,
                    document.fileName(),
                    chunk.getTitle(),
                    chunk.getContent(),
                    tokens,
                    vector,
                    chunk.getChunkIndex(),
                    gson.toJson(buildMetadata(document, chunk, chunks.size()))));
        }
        return rows;
    }

    private Map<String, Object> buildMetadata(KnowledgeDocumentRow document, DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", document.objectKey());
        metadata.put("_file_name", document.fileName());
        metadata.put("documentId", document.id().toString());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        return metadata;
    }

    private KnowledgeDocumentSummary loadSummary(UUID documentId) {
        KnowledgeDocumentRow row = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("文档状态读取失败: " + documentId));
        return KnowledgeDocumentSummary.from(row);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "分块失败";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}