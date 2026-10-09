package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.dto.DocumentChunkView;
import org.example.dto.KnowledgeDocumentPreview;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.dto.Result;
import org.example.service.DocumentIngestionService;
import org.example.service.DocumentUploadService;
import org.example.service.KnowledgeBaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档接口：上传、列表、分块与删除。异常由 GlobalExceptionHandler 统一处理。
 */
@RestController
@RequestMapping("/api/knowledge-base")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final DocumentUploadService documentUploadService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentIngestionService documentIngestionService;

    @PostMapping(value = "/documents", consumes = "multipart/form-data")
    public ResponseEntity<Result<KnowledgeDocumentSummary>> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(Result.ok(documentUploadService.upload(file)));
    }

    @GetMapping("/documents")
    public ResponseEntity<Result<List<KnowledgeDocumentSummary>>> listDocuments() {
        return ResponseEntity.ok(Result.ok(knowledgeBaseService.listDocuments()));
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<Result<KnowledgeDocumentPreview>> preview(@PathVariable("id") String id) {
        return ResponseEntity.ok(Result.ok(knowledgeBaseService.preview(id)));
    }

    @GetMapping("/documents/{id}/chunks")
    public ResponseEntity<Result<List<DocumentChunkView>>> chunks(@PathVariable("id") String id) {
        return ResponseEntity.ok(Result.ok(knowledgeBaseService.listChunks(id)));
    }

    @DeleteMapping("/documents")
    public ResponseEntity<Result<Void>> deleteDocument(@RequestParam("id") String id) {
        knowledgeBaseService.deleteDocument(id);
        return ResponseEntity.ok(Result.ok());
    }

    @PostMapping("/documents/{id}/chunk")
    public ResponseEntity<Result<KnowledgeDocumentSummary>> chunkDocument(@PathVariable("id") String id) {
        return ResponseEntity.ok(Result.ok(documentIngestionService.ingest(id)));
    }
}
