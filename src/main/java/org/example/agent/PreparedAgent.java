package org.example.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 一次请求使用的模型、系统提示词和工具调用参数。
 * 工具循环由 ChatClient 的 ToolCallingAdvisor 驱动。
 */
public record PreparedAgent(ChatModel chatModel, String systemPrompt, OpenAiChatOptions options) {
}
