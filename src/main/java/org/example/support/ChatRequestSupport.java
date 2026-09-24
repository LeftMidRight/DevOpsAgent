package org.example.support;

import org.example.agent.AgentTaskPolicy;
import org.example.agent.TaskMode;
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

    public static TaskMode parseMode(ChatRequest request) {
        return TaskMode.parse(request == null ? null : request.getMode());
    }

    public static String resolveOpsQuestion(ChatRequest request) {
        if (request != null
                && request.getQuestion() != null
                && !request.getQuestion().isBlank()) {
            return request.getQuestion().trim();
        }
        return AgentTaskPolicy.DEFAULT_OPS_QUESTION;
    }

    public static String sessionId(ChatRequest request) {
        return request == null ? null : request.getId();
    }
}
