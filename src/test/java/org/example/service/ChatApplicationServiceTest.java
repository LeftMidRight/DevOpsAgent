package org.example.service;

import org.example.agent.AgentExecutionService;
import org.example.agent.TaskMode;
import org.example.dto.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceTest {

    @Mock
    private AgentExecutionService agentExecutionService;

    @InjectMocks
    private ChatApplicationService chatApplicationService;

    @Test
    void prepareChat_blankQuestion_throws() {
        ChatRequest request = new ChatRequest();
        request.setQuestion("  ");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> chatApplicationService.prepareChat(request));

        assertEquals("问题内容不能为空", error.getMessage());
        verifyNoInteractions(agentExecutionService);
    }

    @Test
    void prepareChat_normalizesQuestion() {
        ChatRequest request = new ChatRequest();
        request.setId("session-1");
        request.setQuestion("  hello  ");
        request.setMode("ops");

        ChatApplicationService.PreparedChat prepared = chatApplicationService.prepareChat(request);

        assertEquals("session-1", prepared.sessionId());
        assertEquals("hello", prepared.question());
        assertEquals(TaskMode.CHAT, prepared.mode());
    }
}
