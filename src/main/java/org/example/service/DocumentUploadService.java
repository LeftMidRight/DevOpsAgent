package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 阶段一：上传。文件写入 MinIO 对象存储并登记文档元数据，随后自动触发分块。
 */
@Service
public class DocumentUploadService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentUploadService.class);

    private final ObjectStorageService objectStorageService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkingService chunkingService;

    public DocumentUploadService(
            ObjectStorageService objectStorageService,
            DocumentRepository documentRepository,
            DocumentChunkingService chunkingService) {
        this.objectStorageService = objectStorageService;
        this.documentRepository = documentRepository;
        this.chunkingService = chunkingService;
    }

    /**
     * 上传文档：写入对象存储、登记文档行，并自动执行分块。
     * 分块失败不抛出异常，返回 FAILED 状态的摘要供前端重试。
     */
    public KnowledgeDocumentSummary uploadDocument(String originalFileName, long size, InputStream content) {
        String safeName = sanitizeFileName(originalFileName);
        UUID documentId = UUID.randomUUID();
        String objectKey = buildObjectKey(documentId, safeName);

        objectStorageService.putObject(objectKey, content, size, contentTypeOf(safeName));
        try {
            documentRepository.insert(documentId, safeName, objectKey, size);
        } catch (RuntimeException e) {
            objectStorageService.removeObject(objectKey);
            throw e;
        }
        logger.info("文档上传完成 - documentId: {}, fileName: {}, objectKey: {}, size: {}",
                documentId, safeName, objectKey, size);
        return chunkingService.chunkDocument(documentId);
    }

    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("非法文件名");
        }
        String normalized = Paths.get(fileName).normalize().getFileName().toString();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("非法文件名");
        }
        return normalized;
    }

    private String buildObjectKey(UUID documentId, String safeName) {
        return "documents/" + documentId + "/" + safeName;
    }

    private String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }
}