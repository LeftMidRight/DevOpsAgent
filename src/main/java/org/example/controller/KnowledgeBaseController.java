package org.example.controller;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.dto.Result;
import org.example.service.DocumentChunkingService;
import org.example.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 知识库管理接口：文档列表、删除与重新分块。
 */
@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentChunkingService documentChunkingService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            DocumentChunkingService documentChunkingService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentChunkingService = documentChunkingService;
    }

    @GetMapping("/documents")
    public ResponseEntity<Result<List<KnowledgeDocumentSummary>>> listDocuments() {
        try {
            return ResponseEntity.ok(Result.ok(knowledgeBaseService.listDocuments()));
        } catch (Exception e) {
            logger.error("查询知识库文档失败", e);
            return ResponseEntity.ok(Result.fail("查询知识库失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/documents")
    public ResponseEntity<Result<Void>> deleteDocument(@RequestParam("id") String id) {
        UUID documentId = parseDocumentId(id);
        if (documentId == null) {
            return ResponseEntity.ok(Result.fail(400, "非法文档ID"));
        }
        try {
            knowledgeBaseService.deleteDocument(documentId);
            return ResponseEntity.ok(Result.ok());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(404, e.getMessage()));
        } catch (Exception e) {
            logger.error("删除知识库文档失败 - id: {}", id, e);
            return ResponseEntity.ok(Result.fail("删除失败: " + e.getMessage()));
        }
    }

    @PostMapping("/documents/{id}/chunk")
    public ResponseEntity<Result<KnowledgeDocumentSummary>> chunkDocument(@PathVariable("id") String id) {
        UUID documentId = parseDocumentId(id);
        if (documentId == null) {
            return ResponseEntity.ok(Result.fail(400, "非法文档ID"));
        }
        try {
            return ResponseEntity.ok(Result.ok(documentChunkingService.chunkDocument(documentId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(404, e.getMessage()));
        } catch (Exception e) {
            logger.error("重新分块失败 - id: {}", id, e);
            return ResponseEntity.ok(Result.fail("重新分块失败: " + e.getMessage()));
        }
    }

    private UUID parseDocumentId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}