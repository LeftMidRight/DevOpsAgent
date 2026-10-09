package org.example.retrieval;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

/**
 * Eval-only 固定 RAG 生成器：只看问题和检索上下文，不注册工具、不走 Agent。
 */
public class PureRagAnswerGenerator {

    public static final double TEMPERATURE = 0.0;
    public static final int MAX_TOKENS = 1024;

    static final String SYSTEM_PROMPT = """
            你是内部知识库问答助手。
            只能根据给定 Context 回答问题。
            如果 Context 中没有足够信息，请明确说明无法从知识库回答。
            不要使用外部知识。
            不要调用任何工具。
            不要补充 Context 中不存在的事实。
            """;

    private final ChatModel chatModel;
    private final String modelName;

    public PureRagAnswerGenerator(ChatModel chatModel, String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Pure RAG 生成模型名不能为空");
        }
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    public String generate(String question, List<RetrievedChunk> chunks) {
        List<String> contexts = chunks == null
                ? List.of()
                : chunks.stream().map(chunk -> chunk.getContent() == null ? "" : chunk.getContent()).toList();
        Prompt prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userPrompt(question, contexts))),
                OpenAiChatOptions.builder()
                        .model(modelName)
                        .temperature(TEMPERATURE)
                        .maxTokens(MAX_TOKENS)
                        .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                        .build());
        ChatResponse response = chatModel.call(prompt);
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Pure RAG 生成结果为空");
        }
        return text.trim();
    }

    static String userPrompt(String question, List<String> contexts) {
        StringBuilder context = new StringBuilder();
        if (contexts == null || contexts.isEmpty()) {
            context.append("（无）");
        } else {
            for (int i = 0; i < contexts.size(); i++) {
                if (i > 0) {
                    context.append("\n\n");
                }
                context.append('[').append(i + 1).append("] ").append(contexts.get(i));
            }
        }
        return "Question:\n" + question + "\n\nContext:\n" + context;
    }
}
