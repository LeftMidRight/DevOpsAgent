package org.example.trajectory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TrajectoryEvent(
        int schemaVersion,
        UUID eventId,
        UUID runId,
        String conversationId,
        UUID requestId,
        long sequence,
        Instant timestamp,
        String type,
        String agent,
        Map<String, Object> payload) {
}
