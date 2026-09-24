package org.example.controller;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.dto.Result;
import org.example.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
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
    public ResponseEntity<Result<Void>> deleteDocument(@RequestParam("fileName") String fileName) {
        try {
            knowledgeBaseService.deleteDocument(fileName);
            return ResponseEntity.ok(Result.ok());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Result.fail(400, e.getMessage()));
        } catch (Exception e) {
            logger.error("删除知识库文档失败 - fileName: {}", fileName, e);
            return ResponseEntity.ok(Result.fail("删除失败: " + e.getMessage()));
        }
    }
}
