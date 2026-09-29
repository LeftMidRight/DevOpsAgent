package org.example.controller;

import org.example.dto.ChatAnswer;
import org.example.dto.ChatRequest;
import org.example.dto.Result;
import org.example.controller.support.AgentSseStreamer;
import org.example.service.ChatApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatApplicationService chatApplicationService;

    @Mock
    private AgentSseStreamer agentSseStreamer;

    @InjectMocks
    private ChatController controller;

    @Test
    void chat_delegatesToChatApplicationService() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setId("session-100");
        request.setQuestion("测试问题");
        when(chatApplicationService.chat(request))
                .thenReturn(new ChatAnswer("session-100", "模型回答内容", "思考过程"));

        ResponseEntity<Result<ChatAnswer>> response = controller.chat(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals("session-100", response.getBody().getData().sessionId());
        assertEquals("模型回答内容", response.getBody().getData().answer());
        assertEquals("思考过程", response.getBody().getData().reasoning());
        verify(chatApplicationService).chat(request);
    }

    @Test
    void chatStream_delegatesToStreamer() {
        ChatRequest request = new ChatRequest();
        request.setQuestion("hello");
        SseEmitter emitter = new SseEmitter();
        when(agentSseStreamer.startChatStream(request, 300_000L)).thenReturn(emitter);

        assertSame(emitter, controller.chatStream(request));
    }
}
