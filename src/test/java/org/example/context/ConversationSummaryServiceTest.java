package org.example.context;

import org.example.conversation.ConversationMessage;
import org.example.conversation.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ConversationSummaryServiceTest {

    private ConversationSummaryService summaryService;

    @BeforeEach
    void setUp() {
        summaryService = new ConversationSummaryService(
                mock(ConversationRepository.class), new ContextProperties(), new TokenEstimator());
    }

    @Test
    void keepsNewestCompleteTurnWhenItFits() {
        List<ConversationMessage> messages = List.of(
                message(1, "user", 5),
                message(2, "assistant", 5),
                message(3, "user", 5),
                message(4, "assistant", 5));

        assertEquals(2, summaryService.findPrefixToCompress(messages, 10));
    }

    @Test
    void summarizesWholeTurnWhenOnlyAssistantSideFits() {
        List<ConversationMessage> messages = List.of(
                message(1, "user", 8),
                message(2, "assistant", 5));

        assertEquals(2, summaryService.findPrefixToCompress(messages, 5));
    }

    @Test
    void doesNotCompressWhenEverythingFits() {
        List<ConversationMessage> messages = List.of(
                message(1, "user", 5),
                message(2, "assistant", 5));

        assertEquals(0, summaryService.findPrefixToCompress(messages, 10));
    }

    private ConversationMessage message(long sequence, String role, int tokenCount) {
        return new ConversationMessage(
                UUID.randomUUID(),
                "conversation",
                UUID.randomUUID(),
                sequence,
                role,
                role + " message",
                "COMMITTED",
                tokenCount,
                Instant.now());
    }
}
