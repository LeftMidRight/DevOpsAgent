package org.example.support;

import org.example.dto.ChatRequest;

/**
 * 聊天请求校验与字段归一化。
 */
public final class ChatRequestSupport {

    private ChatRequestSupport() {
    }

    public static boolean hasBlankQuestion(ChatRequest request) {
        return request == null
                || request.getQuestion() == null
                || request.getQuestion().trim().isEmpty();
    }

    public static String normalizedQuestion(ChatRequest request) {
        return request.getQuestion().trim();
    }
}
