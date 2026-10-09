package org.example.conversation;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按会话读取已经提交到 JVM 的消息。
 * 不在模型调用前写入：Spring AI 的 MessageChatMemoryAdvisor 会在成功前提前 add，
 * 也会把工具消息写入记忆。正式 Turn 只经过 Turn Commit。
 */
@Component
public class SuperBizChatMemory implements ChatMemory {

    private final RuntimeConversationStore store;

    public SuperBizChatMemory(RuntimeConversationStore store) {
        this.store = store;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        throw new UnsupportedOperationException("会话消息只能在 Turn 成功后提交");
    }

    @Override
    public List<Message> get(String conversationId) {
        return store.get(conversationId)
                .map(RuntimeConversationState::messages)
                .orElse(List.of());
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}
