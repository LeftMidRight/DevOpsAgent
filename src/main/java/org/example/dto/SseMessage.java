package org.example.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 统一 SSE 流式消息格式。
 * type：content / reasoning / reasoning_final / final / progress / meta / error / done
 */
public record SseMessage(String type, String data) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static SseMessage content(String data) {
        return new SseMessage("content", data);
    }

    public static SseMessage reasoning(String data) {
        return new SseMessage("reasoning", data);
    }

    public static SseMessage reasoningFinal(String data) {
        return new SseMessage("reasoning_final", data);
    }

    public static SseMessage finalAnswer(String data) {
        return new SseMessage("final", data);
    }

    public static SseMessage progress(String data) {
        return new SseMessage("progress", data);
    }

    public static SseMessage meta(SseMetaPayload payload) {
        try {
            return new SseMessage("meta", MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 SSE meta 失败", e);
        }
    }

    public static SseMessage error(String errorMessage) {
        return new SseMessage("error", errorMessage);
    }

    public static SseMessage done() {
        return new SseMessage("done", null);
    }
}
