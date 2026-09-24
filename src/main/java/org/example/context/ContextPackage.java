package org.example.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record ContextPackage(
        List<Message> messages,
        int estimatedTokens,
        int retainedMessageCount,
        long summaryUntilSequence) {
}
