package org.example.controller;

import org.example.agent.AgentExecutionResult;
import org.example.agent.TaskMode;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
    void chat_emptyQuestion_returnsError() {
        ChatRequest request = new ChatRequest();
        request.setId("session-1");
        request.setQuestion("  ");

        ResponseEntity<Result<ChatAnswer>> response = controller.chat(request);

        assertNotNull(response.getBody());
        assertEquals(400, response.getBody().getCode());
        assertEquals("问题内容不能为空", response.getBody().getMessage());
        verifyNoInteractions(chatApplicationService);
    }

    @Test
    void chat_validRequest_delegatesToChatApplicationService() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setId("session-100");
        request.setQuestion("测试问题");
        request.setMode("CHAT");

        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(chatApplicationService.chat(eq(request), eq(TaskMode.CHAT))).thenReturn(
                new AgentExecutionResult("session-100", requestId, runId, TaskMode.CHAT, "模型回答内容", "思考过程"));

        ResponseEntity<Result<ChatAnswer>> response = controller.chat(request);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());
        assertEquals("session-100", response.getBody().getData().sessionId());
        assertEquals("模型回答内容", response.getBody().getData().answer());
        assertEquals("思考过程", response.getBody().getData().reasoning());
        verify(chatApplicationService).chat(request, TaskMode.CHAT);
    }

    @Test
    void chatStream_emptyQuestion_returnsErrorImmediately() {
        ChatRequest request = new ChatRequest();
        request.setQuestion("");

        SseEmitter emitter = controller.chatStream(request);
        assertNotNull(emitter);
        verify(agentSseStreamer).sendAndComplete(same(emitter), any());
        verify(agentSseStreamer, never()).startStream(any(), any(), any(), anyLong());
    }

    @Test
    void chatStream_validRequest_delegatesToStreamer() {
        ChatRequest request = new ChatRequest();
        request.setId("session-1");
        request.setQuestion("hello");
        request.setMode("CHAT");

        SseEmitter emitter = new SseEmitter();
        when(agentSseStreamer.startStream("session-1", "hello", TaskMode.CHAT, 300_000L))
                .thenReturn(emitter);

        assertSame(emitter, controller.chatStream(request));
    }
}
