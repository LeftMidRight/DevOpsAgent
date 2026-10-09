package org.example.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 缓存命中直接返回 JVM 状态；未命中时按会话懒加载已持久化历史和工作状态快照。
 */
@Service
public class ConversationHydrator {

    private static final Logger logger = LoggerFactory.getLogger(ConversationHydrator.class);

    private final ConversationHistoryPersistence history;
    private final RuntimeConversationStore store;

    public ConversationHydrator(ConversationHistoryPersistence history, RuntimeConversationStore store) {
        this.history = history;
        this.store = store;
    }

    public RuntimeConversationState getActive(String conversationId) {
        return store.get(conversationId).orElseGet(() -> hydrate(conversationId));
    }

    private RuntimeConversationState hydrate(String conversationId) {
        logger.info("会话缓存未命中，从持久化历史恢复 - SessionId: {}", conversationId);
        RuntimeConversationState state = history.load(conversationId)
                .map(record -> RuntimeConversationState.restore(conversationId, record))
                .orElseGet(() -> RuntimeConversationState.empty(conversationId));
        store.put(state);
        return state;
    }
}
