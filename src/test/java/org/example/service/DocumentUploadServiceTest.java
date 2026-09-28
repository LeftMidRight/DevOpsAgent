package org.example.service;

import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentUploadServiceTest {

    @Mock
    private ObjectStorageService objectStorageService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkingService chunkingService;

    @InjectMocks
    private DocumentUploadService uploadService;

    @Test
    void uploadDocument_storesObjectThenRegistersAndTriggersChunking() {
        when(chunkingService.chunkDocument(any(UUID.class))).thenReturn(summary("INDEXED", null));

        KnowledgeDocumentSummary result = uploadService.uploadDocument("guide.md", 12L, content("hello"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(objectStorageService).putObject(keyCaptor.capture(), any(InputStream.class), eq(12L), eq("text/markdown"));

        assertTrue(keyCaptor.getValue().matches("documents/[0-9a-fA-F-]{36}/guide\\.md"),
                "objectKey 应由文档 ID 与文件名组成: " + keyCaptor.getValue());

        InOrder inOrder = inOrder(objectStorageService, documentRepository, chunkingService);
        inOrder.verify(objectStorageService).putObject(eq(keyCaptor.getValue()), any(InputStream.class), eq(12L), eq("text/markdown"));
        inOrder.verify(documentRepository).insert(idCaptor.capture(), eq("guide.md"), eq(keyCaptor.getValue()), eq(12L));
        inOrder.verify(chunkingService).chunkDocument(idCaptor.getValue());

        assertEquals("INDEXED", result.status());
    }

    @Test
    void uploadDocument_removesObjectWhenRegistrationFails() {
        doThrow(new RuntimeException("db down"))
                .when(documentRepository).insert(any(UUID.class), anyString(), anyString(), anyLong());

        assertThrows(RuntimeException.class,
                () -> uploadService.uploadDocument("guide.md", 3L, content("hi")));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStorageService).removeObject(keyCaptor.capture());
        assertTrue(keyCaptor.getValue().startsWith("documents/"));
        verify(chunkingService, never()).chunkDocument(any(UUID.class));
    }

    @Test
    void uploadDocument_returnsFailedSummaryWithoutThrowing() {
        when(chunkingService.chunkDocument(any(UUID.class))).thenReturn(summary("FAILED", "embedding boom"));

        KnowledgeDocumentSummary result = uploadService.uploadDocument("guide.md", 3L, content("hi"));

        assertEquals("FAILED", result.status());
        assertEquals("embedding boom", result.errorMessage());
        verify(documentRepository).insert(any(UUID.class), eq("guide.md"), anyString(), eq(3L));
    }

    @Test
    void sanitizeFileName_rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> DocumentUploadService.sanitizeFileName("../secret.txt"));
    }

    @Test
    void sanitizeFileName_keepsBaseName() {
        assertEquals("guide.md", DocumentUploadService.sanitizeFileName("guide.md"));
    }

    private static InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes());
    }

    private static KnowledgeDocumentSummary summary(String status, String errorMessage) {
        return new KnowledgeDocumentSummary(
                UUID.randomUUID().toString(), "guide.md", "md", 1, 3L, "2026-09-28 10:00", status, errorMessage);
    }
}