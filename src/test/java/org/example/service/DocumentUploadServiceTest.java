package org.example.service;

import org.example.config.FileUploadConfig;
import org.example.dto.KnowledgeDocumentSummary;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeDocumentRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
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
    private FileUploadConfig fileUploadConfig;

    @InjectMocks
    private DocumentUploadService uploadService;

    @Test
    void uploadDocument_storesObjectThenRegistersWithoutChunking() {
        when(documentRepository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.of(
                uploadedRow(invocation.getArgument(0), "guide.md", 12L)));

        KnowledgeDocumentSummary result = uploadService.uploadDocument("guide.md", 12L, content("hello"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(objectStorageService).putObject(keyCaptor.capture(), any(InputStream.class), eq(12L), eq("text/markdown"));

        assertTrue(keyCaptor.getValue().matches("documents/[0-9a-fA-F-]{36}/guide\\.md"),
                "objectKey 应由文档 ID 与文件名组成: " + keyCaptor.getValue());

        InOrder inOrder = inOrder(objectStorageService, documentRepository);
        inOrder.verify(objectStorageService).putObject(eq(keyCaptor.getValue()), any(InputStream.class), eq(12L), eq("text/markdown"));
        inOrder.verify(documentRepository).insert(idCaptor.capture(), eq("guide.md"), eq(keyCaptor.getValue()), eq(12L));
        inOrder.verify(documentRepository).findById(idCaptor.getValue());

        assertEquals(DocumentRepository.STATUS_UPLOADED, result.status());
        assertEquals(0, result.chunkCount());
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
        verify(documentRepository, never()).findById(any());
    }

    @Test
    void upload_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown", new byte[0]);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> uploadService.upload(file));

        assertEquals("文件不能为空", error.getMessage());
        verifyNoInteractions(objectStorageService, documentRepository);
    }

    @Test
    void upload_rejectsMissingFileName() {
        MockMultipartFile file = new MockMultipartFile("file", "", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> uploadService.upload(file));

        assertEquals("文件名不能为空", error.getMessage());
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void upload_rejectsUnsupportedExtension() {
        when(fileUploadConfig.getAllowedExtensions()).thenReturn("txt,md");
        MockMultipartFile file = new MockMultipartFile(
                "file", "guide.pdf", "application/pdf", "hi".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> uploadService.upload(file));

        assertEquals("不支持的文件格式，仅支持: txt,md", error.getMessage());
        verifyNoInteractions(objectStorageService);
    }

    @Test
    void upload_storesAllowedFile() {
        when(fileUploadConfig.getAllowedExtensions()).thenReturn("txt,md");
        when(documentRepository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.of(
                uploadedRow(invocation.getArgument(0), "notes.txt", 2L)));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));

        KnowledgeDocumentSummary result = uploadService.upload(file);

        verify(objectStorageService).putObject(contains("/notes.txt"), any(InputStream.class), eq(2L), eq("text/plain"));
        assertEquals(DocumentRepository.STATUS_UPLOADED, result.status());
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

    private static KnowledgeDocumentRow uploadedRow(UUID id, String fileName, long size) {
        return new KnowledgeDocumentRow(
                id, fileName, "documents/" + id + "/" + fileName, size,
                DocumentRepository.STATUS_UPLOADED, 0, null, Instant.parse("2026-09-29T02:00:00Z"), null);
    }
}