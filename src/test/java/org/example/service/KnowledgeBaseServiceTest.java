package org.example.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseServiceTest {

    @Test
    void sanitizeFileName_rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseService.sanitizeFileName("../secret.txt"));
    }

    @Test
    void sanitizeFileName_keepsBaseName() {
        assertEquals("guide.md", KnowledgeBaseService.sanitizeFileName("guide.md"));
    }
}
