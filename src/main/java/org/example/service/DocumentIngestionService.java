package org.example.service;

import com.google.gson.Gson;
import org.example.chunking.MarkdownChunker;
import org.example.chunking.MarkdownParser;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.parsing.DocumentExtensionSupport;
import org.example.parsing.DocumentParseException;
import org.example.parsing.DocumentParserRegistry;
import org.example.parsing.DocumentSource;
import org.example.parsing.ParsedDocument;
import org.example.parsing.mineru.MinerUAgentDocumentParser;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.example.retrieval.ChunkRecord;
import org.example.retrieval.ChineseTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    public static final String PARSE_ERROR_PREFIX = "[PARSE] ";
    public static final String INDEX_ERROR_PREFIX = "[INDEX] ";

    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestionService.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final DocumentRepository documentRepository;
    private final ObjectStorageService objectStorageService;
    private final DocumentParserRegistry parserRegistry;
    private final MarkdownParser markdownParser;
    private final MarkdownChunker markdownChunker;
    private final VectorEmbeddingService embeddingService;
    private final ChunkRepository chunkRepository;
    private final ChineseTokenizer tokenizer = new ChineseTokenizer();
    private final Gson gson = new Gson();

    public DocumentIngestionService(
            DocumentRepository documentRepository,
            ObjectStorageService objectStorageService,
            DocumentParserRegistry parserRegistry,
            MarkdownParser markdownParser,
            MarkdownChunker markdownChunker,
            VectorEmbeddingService embeddingService,
            ChunkRepository chunkRepository) {
        this.documentRepository = documentRepository;
        this.objectStorageService = objectStorageService;
        this.parserRegistry = parserRegistry;
        this.markdownParser = markdownParser;
        this.markdownChunker = markdownChunker;
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
    }

    public KnowledgeDocumentSummary ingest(String documentId) {
        return ingest(DocumentIds.parse(documentId));
    }

    public KnowledgeDocumentSummary ingest(UUID documentId) {
        KnowledgeDocumentRow document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("文档不存在: " + documentId));
        documentRepository.markProcessing(documentId);
        try {
            ParsedDocument parsed = parseDocument(document);
            persistParsedArtifactIfNeeded(documentId, parsed);
            List<ChunkDraft> drafts = markdownChunker.chunk(
                    markdownParser.parse(parsed.content()), document.fileName());
            List<ChunkRecord> rows = buildChunkRecords(document, parsed, drafts);
            chunkRepository.replaceDocumentChunks(documentId, document.fileName(), rows);
            documentRepository.markIndexed(documentId, rows.size());
            logger.info("文档索引完成 - documentId: {}, fileName: {}, parser: {}, 分片数: {}",
                    documentId, document.fileName(), parsed.parser(), rows.size());
        } catch (DocumentParseException e) {
            documentRepository.markFailed(documentId, truncate(PARSE_ERROR_PREFIX + e.getMessage()));
            logger.error("文档解析失败 - documentId: {}, fileName: {}", documentId, document.fileName(), e);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            documentRepository.markFailed(documentId, truncate(INDEX_ERROR_PREFIX + message));
            logger.error("文档索引失败 - documentId: {}, fileName: {}", documentId, document.fileName(), e);
        }
        return loadSummary(documentId);
    }

    private ParsedDocument parseDocument(KnowledgeDocumentRow document) {
        String parsedKey = document.parsedObjectKey();
        if (parsedKey != null && !parsedKey.isBlank() && objectStorageService.objectExists(parsedKey)) {
            byte[] cached = objectStorageService.getObject(parsedKey);
            String text = new String(cached, StandardCharsets.UTF_8);
            logger.info("复用已缓存解析结果 - documentId: {}", document.id());
            return MinerUAgentDocumentParser.fromMarkdown(document.id(), document.fileName(), text);
        }
        byte[] bytes = objectStorageService.getObject(document.objectKey());
        String mediaType = document.mediaType() != null && !document.mediaType().isBlank()
                ? document.mediaType()
                : DocumentExtensionSupport.mimeTypeOf(document.fileName());
        DocumentSource source = new DocumentSource(
                document.id(), document.fileName(), mediaType, document.objectKey(), bytes);
        return parserRegistry.parse(source);
    }

    private void persistParsedArtifactIfNeeded(UUID documentId, ParsedDocument parsed) {
        if (!MinerUAgentDocumentParser.PARSER_NAME.equals(parsed.parser()) || parsed.content().isBlank()) {
            return;
        }
        String key = "parsed/" + documentId + "/content.md";
        objectStorageService.putObjectBytes(key, parsed.content().getBytes(StandardCharsets.UTF_8), "text/markdown");
        documentRepository.updateParsedArtifact(documentId, parsed.parser(), parsed.parserVersion(), key);
    }

    private List<ChunkRecord> buildChunkRecords(
            KnowledgeDocumentRow document, ParsedDocument parsed, List<ChunkDraft> drafts) {
        List<ChunkRecord> rows = new ArrayList<>();
        for (ChunkDraft draft : drafts) {
            String retrievalText = draft.retrievalText() == null || draft.retrievalText().isBlank()
                    ? draft.content()
                    : draft.retrievalText();
            List<Float> vector = embeddingService.generateEmbedding(retrievalText);
            List<String> tokens = tokenizer.tokenize(retrievalText);
            UUID chunkId = UUID.nameUUIDFromBytes(
                    (document.id() + "_" + draft.chunkIndex()).getBytes(StandardCharsets.UTF_8));
            rows.add(new ChunkRecord(
                    chunkId,
                    document.fileName(),
                    draft.title(),
                    draft.content(),
                    retrievalText,
                    tokens,
                    vector,
                    draft.chunkIndex(),
                    gson.toJson(buildMetadata(document, parsed, draft, drafts.size()))));
        }
        return rows;
    }

    private Map<String, Object> buildMetadata(
            KnowledgeDocumentRow document,
            ParsedDocument parsed,
            ChunkDraft draft,
            int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", document.objectKey());
        metadata.put("_file_name", document.fileName());
        metadata.put("documentId", document.id().toString());
        metadata.put("chunkIndex", draft.chunkIndex());
        metadata.put("totalChunks", totalChunks);
        metadata.put("sourceType", parsed.sourceType());
        metadata.put("parser", parsed.parser());
        metadata.put("parserVersion", parsed.parserVersion());
        metadata.put("contentType", draft.contentType());
        if (draft.headingPath() != null && !draft.headingPath().isEmpty()) {
            metadata.put("headingPath", draft.headingPath());
        }
        if (draft.title() != null && !draft.title().isEmpty()) {
            metadata.put("title", draft.title());
        }
        return metadata;
    }

    private KnowledgeDocumentSummary loadSummary(UUID documentId) {
        KnowledgeDocumentRow row = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("文档状态读取失败: " + documentId));
        return KnowledgeDocumentSummary.from(row);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        if (message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
