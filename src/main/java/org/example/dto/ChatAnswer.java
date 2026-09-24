package org.example.dto;

/**
 * 同步聊天成功时的响应数据。
 */
public record ChatAnswer(String sessionId, String answer, String reasoning) {
}
