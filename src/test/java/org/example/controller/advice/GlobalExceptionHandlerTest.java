package org.example.controller.advice;

import org.example.dto.Result;
import org.example.service.DocumentNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void documentNotFound_returns404() {
        ResponseEntity<Result<Void>> response = handler.handleDocumentNotFound(
                new DocumentNotFoundException("文档不存在: x"));

        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getCode());
    }

    @Test
    void illegalArgument_returns400() {
        ResponseEntity<Result<Void>> response = handler.handleIllegalArgument(
                new IllegalArgumentException("非法文档ID"));

        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
    }

    @Test
    void genericException_returns500() {
        ResponseEntity<Result<Void>> response = handler.handleException(new RuntimeException("boom"));

        assertNotNull(response.getBody());
        assertEquals(500, response.getBody().getCode());
        assertEquals("boom", response.getBody().getMessage());
    }
}
