package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public void deleteDocument(String documentId) {
        deleteDocument(DocumentIds.parse(documentId));
    }

    public void deleteDocument(UUID documentId) {
        KnowledgeDocumentRow document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: " + documentId));
        int deletedChunks = chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        objectStorageService.removeObject(document.objectKey());
        logger.info("已删除知识库文档: {}, 分片数: {}", document.fileName(), deletedChunks);
    }
}