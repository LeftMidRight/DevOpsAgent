package org.example.context;

import org.springframework.ai.chat.model.ChatModel;
import org.example.conversation.ConversationMessage;
import org.example.conversation.ConversationRepository;
import org.example.conversation.ConversationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAssemblerTest {

    @Mock
    private ConversationRepository repository;
    @Mock
    private ConversationSummaryService summaryService;
    @Mock
    private ChatModel chatModel;

    private ContextProperties properties;
    private TokenEstimator estimator;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.setRecentMessageTokens(1000);
        properties.setSummaryTokens(200);
        estimator = new TokenEstimator();
        assembler = new ContextAssembler(repository, summaryService, properties, estimator);
    }

    @Test
    void preservesMessageRolesAndPlacesSummaryBeforeRecentHistory() {
        String conversationId = "session-1";
        UUID oldRequest = UUID.randomUUID();
        UUID currentRequest = UUID.randomUUID();
        Instant now = Instant.now();
        ConversationState state = new ConversationState(
                conversationId, "## 当前主题\n排查内存告警", 2, now, now);
        List<ConversationMessage> messages = List.of(
                message(conversationId, currentRequest, 3, "user", "继续查日志", "PENDING"),
                message(conversationId, oldRequest, 1, "user", "已被摘要", "COMMITTED"));

        when(repository.find(conversationId)).thenReturn(Optional.of(state));
        when(repository.findContextMessages(conversationId, currentRequest)).thenReturn(messages);

        ContextPackage result = assembler.assemble(conversationId, currentRequest, chatModel);

        verify(summaryService).compressIfNeeded(conversationId, currentRequest, 1000, chatModel);
        assertEquals(2, result.messages().size());
        assertInstanceOf(UserMessage.class, result.messages().get(0));
        assertInstanceOf(UserMessage.class, result.messages().get(1));
        assertEquals("继续查日志", result.messages().get(1).getText());
    }

    @Test
    void retainsNewestMessagesWithinBudget() {
        String conversationId = "session-2";
        UUID oldRequest = UUID.randomUUID();
        UUID currentRequest = UUID.randomUUID();
        Instant now = Instant.now();
        properties.setRecentMessageTokens(20);
        ConversationState state = new ConversationState(conversationId, null, 0, now, now);
        List<ConversationMessage> messages = List.of(
                message(conversationId, oldRequest, 1, "user", "很早以前的一个非常非常长的问题", "COMMITTED"),
                message(conversationId, oldRequest, 2, "assistant", "很早以前的一个非常非常长的回答", "COMMITTED"),
                message(conversationId, currentRequest, 3, "user", "当前问题", "PENDING"));

        when(repository.find(conversationId)).thenReturn(Optional.of(state));
        when(repository.findContextMessages(conversationId, currentRequest)).thenReturn(messages);

        ContextPackage result = assembler.assemble(conversationId, currentRequest, chatModel);

        assertEquals(1, result.messages().size());
        assertInstanceOf(UserMessage.class, result.messages().get(0));
        assertEquals("当前问题", result.messages().get(0).getText());
    }

    private ConversationMessage message(
            String conversationId,
            UUID requestId,
            long sequence,
            String role,
            String content,
            String status) {
        return new ConversationMessage(
                UUID.randomUUID(), conversationId, requestId, sequence, role, content, status,
                estimator.estimate(content), Instant.now());
    }
}
