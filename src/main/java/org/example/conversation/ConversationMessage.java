package org.example.conversation;

import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(
        UUID id,
        String conversationId,
        UUID requestId,
        long sequence,
        String role,
        String content,
        String status,
        int tokenCount,
        Instant createdAt) {
}
