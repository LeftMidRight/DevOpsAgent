package org.example.conversation;

import org.example.conversation.ConversationHistoryPersistence.DurableConversationRecord;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 某个会话在 JVM 中的活跃状态：已提交消息、跨轮任务状态和版本。
 * 一次 Agent Run 的预算、证据和取消不进入这里。
 */
public final class RuntimeConversationState {

    private final String conversationId;
    private final Deque<Message> messages = new ArrayDeque<>();
    private WorkingState workingState = new WorkingState();
    private long turnRevision;
    private long persistedRevision;
    private volatile Instant lastAccessAt = Instant.EPOCH;

    private RuntimeConversationState(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        this.conversationId = conversationId;
    }

    public static RuntimeConversationState empty(String conversationId) {
        return new RuntimeConversationState(conversationId);
    }

    public static RuntimeConversationState restore(String conversationId, DurableConversationRecord record) {
        RuntimeConversationState state = new RuntimeConversationState(conversationId);
        ConversationMessage pendingUser = null;
        for (ConversationMessage message : record.messages()) {
            if (!"COMMITTED".equals(message.status())) {
                continue;
            }
            if ("user".equals(message.role())) {
                pendingUser = message;
            } else if ("assistant".equals(message.role()) && pendingUser != null) {
                state.messages.addLast(new UserMessage(pendingUser.content()));
                state.messages.addLast(new AssistantMessage(message.content()));
                pendingUser = null;
            }
        }
        state.workingState = record.workingState() == null
                ? new WorkingState()
                : record.workingState().copy();
        state.turnRevision = record.revision();
        state.persistedRevision = record.revision();
        return state;
    }

    public String conversationId() {
        return conversationId;
    }

    public synchronized List<Message> messages() {
        return List.copyOf(messages);
    }

    public synchronized WorkingState workingState() {
        return workingState.copy();
    }

    public synchronized long turnRevision() {
        return turnRevision;
    }

    public synchronized long persistedRevision() {
        return persistedRevision;
    }

    public Instant lastAccessAt() {
        return lastAccessAt;
    }

    public void touch(Instant accessedAt) {
        this.lastAccessAt = accessedAt;
    }

    public synchronized void commitTurn(
            UserMessage userMessage,
            String assistantText,
            WorkingState draft,
            long revision) {
        messages.addLast(userMessage);
        messages.addLast(new AssistantMessage(assistantText));
        workingState = draft.copy();
        turnRevision = revision;
        persistedRevision = revision;
    }
}
