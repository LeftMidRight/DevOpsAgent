package org.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningContentExtractorTest {

    @Test
    void extractsReasoningFromMetadata() {
        Message message = testMessage(Map.of(ReasoningContentExtractor.METADATA_KEY, "step by step"));

        assertEquals("step by step", ReasoningContentExtractor.extract(message));
    }

    @Test
    void ignoresBlankReasoning() {
        Message message = testMessage(Map.of(ReasoningContentExtractor.METADATA_KEY, "   "));

        assertNull(ReasoningContentExtractor.extract(message));
    }

    private static Message testMessage(Map<String, Object> metadata) {
        return new Message() {
            @Override
            public MessageType getMessageType() {
                return MessageType.ASSISTANT;
            }

            @Override
            public String getText() {
                return "answer";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return metadata;
            }
        };
    }
}
