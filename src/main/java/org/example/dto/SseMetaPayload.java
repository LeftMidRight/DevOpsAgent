package org.example.dto;

/**
 * SSE meta 事件 payload。
 */
public record SseMetaPayload(
        String sessionId,
        String requestId,
        String runId,
        String mode
) {
}
