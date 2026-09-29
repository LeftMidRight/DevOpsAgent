package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.ChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.example.retrieval.ChunkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentChunkingServiceTest {

    private static final byte[] CONTENT = "# 标题\n\n内容".getBytes(StandardCharsets.UTF_8);

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private VectorEmbeddingService embeddingService;

    @Mock
    private ChunkRepository chunkRepository;

    private DocumentChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        DocumentChunkService documentChunkService = new DocumentChunkService();
        org.example.config.DocumentChunkConfig chunkConfig = new org.example.config.DocumentChunkConfig();
        chunkConfig.setMaxSize(800);
        chunkConfig.setOverlap(100);
        ReflectionTestUtils.setField(documentChunkService, "chunkConfig", chunkConfig);

        chunkingService = new DocumentChunkingService(
                documentRepository, objectStorageService, documentChunkService, embeddingService, chunkRepository);
    }

    @Test
    void chunkDocument_indexesChunksAndMarksIndexed() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(row(documentId, DocumentRepository.STATUS_UPLOADED, 0)),
                        Optional.of(row(documentId, DocumentRepository.STATUS_INDEXED, 1)));
        when(objectStorageService.getObject(anyString())).thenReturn(CONTENT);
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));

        KnowledgeDocumentSummary result = chunkingService.chunkDocument(documentId);

        ArgumentCaptor<List<ChunkRecord>> rowsCaptor = chunksCaptor();
        verify(chunkRepository).replaceDocumentChunks(eq(documentId), eq("guide.md"), rowsCaptor.capture());

        List<ChunkRecord> rows = rowsCaptor.getValue();
        assertEquals(1, rows.size());
        assertEquals(UUID.nameUUIDFromBytes((documentId + "_0").getBytes(StandardCharsets.UTF_8)), rows.get(0).id());
        assertEquals("guide.md", rows.get(0).fileName());
        verify(documentRepository).markIndexed(documentId, 1);
        assertEquals(DocumentRepository.STATUS_INDEXED, result.status());
    }

    @Test
    void chunkDocument_marksFailedAndReturnsFailedSummaryWhenEmbeddingFails() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(row(documentId, DocumentRepository.STATUS_UPLOADED, 0)),
                        Optional.of(row(documentId, DocumentRepository.STATUS_FAILED, 0)));
        when(objectStorageService.getObject(anyString())).thenReturn(CONTENT);
        when(embeddingService.generateEmbedding(anyString())).thenThrow(new RuntimeException("embedding boom"));

        KnowledgeDocumentSummary result = assertDoesNotThrow(() -> chunkingService.chunkDocument(documentId));

        verify(documentRepository).markFailed(eq(documentId), contains("embedding boom"));
        verify(documentRepository, never()).markIndexed(any(UUID.class), anyInt());
        verify(chunkRepository, never()).replaceDocumentChunks(any(UUID.class), anyString(), anyList());
        assertEquals(DocumentRepository.STATUS_FAILED, result.status());
    }

    @Test
    void chunkDocument_throwsWhenDocumentMissing() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThrows(DocumentNotFoundException.class, () -> chunkingService.chunkDocument(documentId));
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void chunkDocument_rejectsInvalidId() {
        assertThrows(IllegalArgumentException.class, () -> chunkingService.chunkDocument("not-a-uuid"));
        verifyNoInteractions(documentRepository, objectStorageService);
    }

    @Test
    void chunkIds_areDisjointForSameFileNameAcrossDocuments() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(documentRepository.findById(firstId))
                .thenReturn(Optional.of(row(firstId, DocumentRepository.STATUS_UPLOADED, 0)),
                        Optional.of(row(firstId, DocumentRepository.STATUS_INDEXED, 1)));
        when(documentRepository.findById(secondId))
                .thenReturn(Optional.of(row(secondId, DocumentRepository.STATUS_UPLOADED, 0)),
                        Optional.of(row(secondId, DocumentRepository.STATUS_INDEXED, 1)));
        when(objectStorageService.getObject(anyString())).thenReturn(CONTENT);
        when(embeddingService.generateEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));

        chunkingService.chunkDocument(firstId);
        chunkingService.chunkDocument(secondId);

        ArgumentCaptor<List<ChunkRecord>> rowsCaptor = chunksCaptor();
        verify(chunkRepository, times(2)).replaceDocumentChunks(any(UUID.class), eq("guide.md"), rowsCaptor.capture());

        Set<UUID> firstIds = rowsCaptor.getAllValues().get(0).stream()
                .map(ChunkRecord::id).collect(Collectors.toSet());
        Set<UUID> secondIds = rowsCaptor.getAllValues().get(1).stream()
                .map(ChunkRecord::id).collect(Collectors.toSet());
        assertTrue(Collections.disjoint(firstIds, secondIds), "同名文档的分片主键不能冲突");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChunkRecord>> chunksCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private static KnowledgeDocumentRow row(UUID id, String status, int chunkCount) {
        return new KnowledgeDocumentRow(
                id,
                "guide.md",
                "documents/" + id + "/guide.md",
                100L,
                status,
                chunkCount,
                null,
                Instant.now(),
                DocumentRepository.STATUS_INDEXED.equals(status) ? Instant.now() : null);
    }
}