package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 阶段二：分块与索引入口，委托 {@link DocumentIngestionService} 完成解析、分块与向量化。
 */
@Service
public class DocumentChunkingService {

    private final DocumentIngestionService documentIngestionService;

    public DocumentChunkingService(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    public KnowledgeDocumentSummary chunkDocument(String documentId) {
        return documentIngestionService.ingest(documentId);
    }

    public KnowledgeDocumentSummary chunkDocument(UUID documentId) {
        return documentIngestionService.ingest(documentId);
    }
}
