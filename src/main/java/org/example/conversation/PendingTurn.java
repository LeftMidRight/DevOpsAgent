package org.example.conversation;

import org.springframework.ai.chat.messages.UserMessage;

import java.util.UUID;

/**
 * 尚未成功提交的一轮。工作状态是已提交快照的副本，失败时整体丢弃。
 */
public final class PendingTurn {

    private final UUID requestId;
    private final UserMessage userMessage;
    private final WorkingState workingStateDraft;
    private final long expectedRevision;

    private PendingTurn(
            UUID requestId,
            UserMessage userMessage,
            WorkingState workingStateDraft,
            long expectedRevision) {
        this.requestId = requestId;
        this.userMessage = userMessage;
        this.workingStateDraft = workingStateDraft;
        this.expectedRevision = expectedRevision;
    }

    public static PendingTurn begin(
            UUID requestId,
            String question,
            WorkingState committed,
            long expectedRevision) {
        WorkingState draft = committed.copy();
        if (draft.goal() == null || draft.goal().isBlank()) {
            draft.setGoal(question);
        }
        return new PendingTurn(requestId, new UserMessage(question), draft, expectedRevision);
    }

    public UUID requestId() {
        return requestId;
    }

    public UserMessage userMessage() {
        return userMessage;
    }

    public WorkingState workingStateDraft() {
        return workingStateDraft;
    }

    public long expectedRevision() {
        return expectedRevision;
    }
}
