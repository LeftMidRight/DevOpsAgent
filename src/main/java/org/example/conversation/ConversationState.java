package org.example.conversation;

import java.time.Instant;

public record ConversationState(
        String id,
        String summary,
        long summaryUntilSequence,
        Instant createdAt,
        Instant updatedAt) {
}
