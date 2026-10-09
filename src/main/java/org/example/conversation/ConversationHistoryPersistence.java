package org.example.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 已成功 Turn 的持久化历史。活跃会话的模型调用不走这个接口读取上下文。
 */
public interface ConversationHistoryPersistence {

    Optional<DurableConversationRecord> load(String conversationId);

    long commitTurn(TurnCommitCommand commit);

    record TurnCommitCommand(
            String conversationId,
            UUID requestId,
            String userContent,
            String assistantContent,
            WorkingState workingState,
            long expectedRevision
    ) {
    }

    record DurableConversationRecord(
            long revision,
            WorkingState workingState,
            List<ConversationMessage> messages
    ) {
    }
}
