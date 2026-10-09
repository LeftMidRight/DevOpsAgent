package org.example.conversation;

import java.util.Optional;

/**
 * JVM 中的活跃会话存储。缓存未命中时由 {@link ConversationHydrator} 从持久化历史恢复。
 */
public interface RuntimeConversationStore {

    Optional<RuntimeConversationState> get(String conversationId);

    void put(RuntimeConversationState state);

    void remove(String conversationId);

    boolean contains(String conversationId);
}
