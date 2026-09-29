package org.example.controller;

import org.example.dto.Result;
import org.example.dto.ClearRequest;
import org.example.service.SessionApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionApplicationService sessionApplicationService;

    @InjectMocks
    private SessionController controller;

    @Test
    void clearChatHistory_success() throws Exception {
        ClearRequest request = new ClearRequest();
        request.setId("session-to-clear");

        doNothing().when(sessionApplicationService).clearHistory(request);

        ResponseEntity<Result<String>> response = controller.clearChatHistory(request);

        assertNotNull(response.getBody());
        assertEquals(200, response.getBody().getCode());
        assertEquals("会话历史已清空", response.getBody().getData());
        verify(sessionApplicationService).clearHistory(request);
    }
}
