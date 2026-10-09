package org.example.service;

import org.example.dto.DocumentChunkView;
import org.example.dto.KnowledgeDocumentPreview;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.parsing.DocumentExtensionSupport;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.example.retrieval.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 知识库管理：列出文档注册表、删除文档（分片 + 文档行 + 对象存储原文）。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final ObjectStorageService objectStorageService;

    public KnowledgeBaseService(
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            ObjectStorageService objectStorageService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.objectStorageService = objectStorageService;
    }

    public List<KnowledgeDocumentSummary> listDocuments() {
        List<KnowledgeDocumentSummary> summaries = new ArrayList<>();
        for (KnowledgeDocumentRow row : documentRepository.findAll()) {
            summaries.add(KnowledgeDocumentSummary.from(row));
        }
        return summaries;
    }

    public KnowledgeDocumentPreview preview(String documentId) {
        KnowledgeDocumentRow document = requireDocument(documentId);
        String id = document.id().toString();
        if (DocumentExtensionSupport.isPlainText(document.fileName())) {
            return KnowledgeDocumentPreview.original(
                    id, document.fileName(), document.status(), readText(document.objectKey()));
        }
        if (document.parsedObjectKey() != null && !document.parsedObjectKey().isBlank()) {
            return KnowledgeDocumentPreview.parsed(
                    id, document.fileName(), document.status(), readText(document.parsedObjectKey()));
        }
        return KnowledgeDocumentPreview.unavailable(id, document.fileName(), document.status());
    }

    public List<DocumentChunkView> listChunks(String documentId) {
        KnowledgeDocumentRow document = requireDocument(documentId);
        List<DocumentChunkView> views = new ArrayList<>();
        for (ChunkRecord chunk : chunkRepository.findByDocumentId(document.id())) {
            views.add(new DocumentChunkView(chunk.chunkIndex(), chunk.title(), chunk.content()));
        }
        return views;
    }

    public void deleteDocument(String documentId) {
        deleteDocument(DocumentIds.parse(documentId));
    }

    private KnowledgeDocumentRow requireDocument(String documentId) {
        UUID id = DocumentIds.parse(documentId);
        return documentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: " + documentId));
    }

    private String readText(String objectKey) {
        return new String(objectStorageService.getObject(objectKey), StandardCharsets.UTF_8);
    }

    public void deleteDocument(UUID documentId) {
        KnowledgeDocumentRow document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: " + documentId));
        int deletedChunks = chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        objectStorageService.removeObject(document.objectKey());
        if (document.parsedObjectKey() != null && !document.parsedObjectKey().isBlank()) {
            objectStorageService.removeObject(document.parsedObjectKey());
        }
        logger.info("已删除知识库文档: {}, 分片数: {}", document.fileName(), deletedChunks);
    }
}