package org.example.service;

import org.example.dto.DocumentChunkView;
import org.example.dto.KnowledgeDocumentPreview;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.example.retrieval.ChunkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServicePreviewTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(documentRepository, chunkRepository, objectStorageService);
    }

    @Test
    void plainTextPreviewReadsOriginalObject() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.of(row(id, "notes.md", null)));
        when(objectStorageService.getObject("documents/" + id + "/notes.md"))
                .thenReturn("# 标题\n正文".getBytes(StandardCharsets.UTF_8));

        KnowledgeDocumentPreview preview = service.preview(id.toString());

        assertTrue(preview.previewAvailable());
        assertEquals("original", preview.previewKind());
        assertEquals("# 标题\n正文", preview.content());
    }

    @Test
    void binaryPreviewReadsParsedMarkdown() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.of(row(id, "guide.pdf", "parsed/" + id + "/content.md")));
        when(objectStorageService.getObject("parsed/" + id + "/content.md"))
                .thenReturn("解析后的正文".getBytes(StandardCharsets.UTF_8));

        KnowledgeDocumentPreview preview = service.preview(id.toString());

        assertEquals("parsed", preview.previewKind());
        assertEquals("解析后的正文", preview.content());
    }

    @Test
    void binaryWithoutParsedTextCannotBePreviewed() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.of(row(id, "guide.pdf", null)));

        KnowledgeDocumentPreview preview = service.preview(id.toString());

        assertFalse(preview.previewAvailable());
        assertEquals("暂时无法预览", preview.message());
    }

    @Test
    void listChunksKeepsStoredOrderAndOmitsVectors() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.of(row(id, "notes.md", null)));
        when(chunkRepository.findByDocumentId(id)).thenReturn(List.of(
                chunk(0, "告警名称", "第一块"),
                chunk(1, "排查步骤", "第二块")));

        List<DocumentChunkView> chunks = service.listChunks(id.toString());

        assertEquals(List.of(0, 1), chunks.stream().map(DocumentChunkView::chunkIndex).toList());
        assertEquals("排查步骤", chunks.get(1).title());
        assertEquals("第二块", chunks.get(1).content());
    }

    @Test
    void missingDocumentIsRejected() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> service.preview(id.toString()));
    }

    private static KnowledgeDocumentRow row(UUID id, String fileName, String parsedKey) {
        return new KnowledgeDocumentRow(
                id,
                fileName,
                "documents/" + id + "/" + fileName,
                10L,
                "INDEXED",
                2,
                null,
                null,
                null,
                null,
                parsedKey,
                null,
                Instant.now(),
                Instant.now());
    }

    private static ChunkRecord chunk(int index, String title, String content) {
        return new ChunkRecord(UUID.randomUUID(), "notes.md", title, content, null, List.of(), List.of(0.1f), index, null);
    }
}
