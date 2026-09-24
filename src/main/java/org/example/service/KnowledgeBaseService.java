package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentSummaryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeBaseService {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final DateTimeFormatter INDEX_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ChunkRepository chunkRepository;

    @Value("${file.upload.path}")
    private String uploadPath;

    public KnowledgeBaseService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public List<KnowledgeDocumentSummary> listDocuments() {
        List<KnowledgeDocumentSummary> summaries = new ArrayList<>();
        for (DocumentSummaryRow row : chunkRepository.listDocumentSummaries()) {
            summaries.add(toSummary(row));
        }
        return summaries;
    }

    public void deleteDocument(String fileName) {
        String safeName = sanitizeFileName(fileName);
        int deletedChunks = chunkRepository.deleteByFileName(safeName);
        Path filePath = Paths.get(uploadPath).normalize().resolve(safeName).normalize();
        try {
            if (Files.deleteIfExists(filePath)) {
                logger.info("已删除知识库文件: {}", filePath);
            }
        } catch (Exception e) {
            logger.warn("删除知识库文件失败: {} - {}", filePath, e.getMessage());
        }
        logger.info("已删除知识库文档: {}, 分片数: {}", safeName, deletedChunks);
    }

    private KnowledgeDocumentSummary toSummary(DocumentSummaryRow row) {
        String fileName = row.fileName();
        String extension = extensionOf(fileName);
        Long fileSize = resolveFileSize(fileName);
        String indexedAt = INDEX_TIME_FORMAT.format(row.indexedAt());
        return new KnowledgeDocumentSummary(fileName, extension, row.chunkCount(), fileSize, indexedAt);
    }

    private Long resolveFileSize(String fileName) {
        Path filePath = Paths.get(uploadPath).normalize().resolve(fileName).normalize();
        try {
            if (Files.isRegularFile(filePath)) {
                return Files.size(filePath);
            }
        } catch (Exception e) {
            logger.debug("无法读取文件大小: {} - {}", filePath, e.getMessage());
        }
        return null;
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

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
