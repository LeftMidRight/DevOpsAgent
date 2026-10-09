package org.example.conversation;

import org.springframework.stereotype.Service;

/**
 * 一轮成功后的持久化提交。只写数据库，调用方在提交成功后再更新 JVM 状态。
 */
@Service
public class TurnCommitService {

    private final ConversationHistoryPersistence history;

    public TurnCommitService(ConversationHistoryPersistence history) {
        this.history = history;
    }

    public long commit(String conversationId, PendingTurn pending, String answer) {
        return history.commitTurn(new ConversationHistoryPersistence.TurnCommitCommand(
                conversationId,
                pending.requestId(),
                pending.userMessage().getText(),
                answer,
                pending.workingStateDraft().copy(),
                pending.expectedRevision()));
    }
}
