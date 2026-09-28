package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.dto.Result;
import org.example.service.DocumentUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 文档上传接口：文件存入 MinIO 对象存储并自动触发分块。
 * 本地磁盘不再保存文件，分块失败时返回 FAILED 状态供重试。
 */
@RestController
public class FileUploadController {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadController.class);

    private final FileUploadConfig fileUploadConfig;
    private final DocumentUploadService documentUploadService;

    public FileUploadController(FileUploadConfig fileUploadConfig, DocumentUploadService documentUploadService) {
        this.fileUploadConfig = fileUploadConfig;
        this.documentUploadService = documentUploadService;
    }

    @PostMapping(value = "/api/upload", consumes = "multipart/form-data")
    public ResponseEntity<Result<KnowledgeDocumentSummary>> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.ok(Result.fail(400, "文件不能为空"));
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isEmpty()) {
            return ResponseEntity.ok(Result.fail(400, "文件名不能为空"));
        }

        String fileExtension = getFileExtension(originalFilename);
        if (!isAllowedExtension(fileExtension)) {
            return ResponseEntity.ok(Result.fail(400,
                    "不支持的文件格式，仅支持: " + fileUploadConfig.getAllowedExtensions()));
        }

        try {
            KnowledgeDocumentSummary summary = documentUploadService.uploadDocument(
                    originalFilename, file.getSize(), file.getInputStream());
            return ResponseEntity.ok(Result.ok(summary));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.ok(Result.fail("文件上传失败: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("文件上传或分块失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Result.fail(e.getMessage()));
        }
    }

    private String getFileExtension(String filename) {
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
}