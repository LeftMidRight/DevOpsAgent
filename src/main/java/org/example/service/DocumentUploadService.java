package org.example.service;

import org.example.config.FileUploadConfig;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 阶段一：上传。文件写入 MinIO 对象存储并登记文档元数据，状态为 UPLOADED。
 * 分块与向量化由 DocumentChunkingService 单独执行。
 */
@Service
public class DocumentUploadService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentUploadService.class);

    private final ObjectStorageService objectStorageService;
    private final DocumentRepository documentRepository;
    private final FileUploadConfig fileUploadConfig;

    public DocumentUploadService(
            ObjectStorageService objectStorageService,
            DocumentRepository documentRepository,
            FileUploadConfig fileUploadConfig) {
        this.objectStorageService = objectStorageService;
        this.documentRepository = documentRepository;
        this.fileUploadConfig = fileUploadConfig;
    }

    /**
     * 校验上传文件后写入对象存储并自动分块。
     * 参数不合法时抛出 IllegalArgumentException；读取文件失败时抛出 IllegalStateException。
     */
    public KnowledgeDocumentSummary upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String extension = fileExtension(originalFilename);
        if (!isAllowedExtension(extension)) {
            throw new IllegalArgumentException(
                    "不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions());
        }
        try {
            return uploadDocument(originalFilename, file.getSize(), file.getInputStream());
        } catch (IOException e) {
            throw new IllegalStateException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传文档：写入对象存储并登记文档行。不执行分块。
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
        return documentRepository.findById(documentId)
                .map(KnowledgeDocumentSummary::from)
                .orElseThrow(() -> new IllegalStateException("文档登记后读取失败: " + documentId));
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

    private String fileExtension(String filename) {
        int lastIndexOf = filename.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return filename.substring(lastIndexOf + 1).toLowerCase();
    }

    private boolean isAllowedExtension(String extension) {
        String allowedExtensions = fileUploadConfig.getAllowedExtensions();
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return false;
        }
        List<String> allowedList = Arrays.asList(allowedExtensions.split(","));
        return allowedList.contains(extension.toLowerCase());
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