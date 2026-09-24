package org.example.agent;

import org.springframework.ai.chat.messages.Message;

/**
 * 从 Spring AI AssistantMessage metadata 中提取推理内容。
 * OpenAI 兼容 API 的 reasoning_content 字段映射为 reasoningContent。
 */
public final class ReasoningContentExtractor {

    public static final String METADATA_KEY = "reasoningContent";

    private ReasoningContentExtractor() {
    }

    public static String extract(Message message) {
        if (message == null) {
            return null;
        }
        Object value = message.getMetadata().get(METADATA_KEY);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }
}
