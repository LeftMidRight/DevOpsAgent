package org.example.conversation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 测试用持久化历史。一次 commitTurn 同时写入用户消息、助手消息和工作状态。
 */
public final class InMemoryConversationHistory implements ConversationHistoryPersistence {

    private final Map<String, List<TurnCommitCommand>> turns = new HashMap<>();
    private final Map<String, WorkingState> snapshots = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private int loads;
    private boolean failNextCommit;

    @Override
    public synchronized Optional<DurableConversationRecord> load(String conversationId) {
        loads++;
        if (!revisions.containsKey(conversationId)) {
            return Optional.empty();
        }
        List<ConversationMessage> messages = new ArrayList<>();
        long sequence = 0;
        for (TurnCommitCommand turn : turns.getOrDefault(conversationId, List.of())) {
            messages.add(message(turn, "user", turn.userContent(), sequence++));
            messages.add(message(turn, "assistant", turn.assistantContent(), sequence++));
        }
        WorkingState snapshot = snapshots.getOrDefault(conversationId, new WorkingState()).copy();
        return Optional.of(new DurableConversationRecord(
                revisions.get(conversationId), snapshot, List.copyOf(messages)));
    }

    @Override
    public synchronized long commitTurn(TurnCommitCommand commit) {
        if (failNextCommit) {
            failNextCommit = false;
            throw new IllegalStateException("数据库提交失败");
        }
        long current = revisions.getOrDefault(commit.conversationId(), 0L);
        if (current != commit.expectedRevision()) {
            throw new IllegalStateException("会话版本冲突");
        }
        turns.computeIfAbsent(commit.conversationId(), id -> new ArrayList<>()).add(commit);
        snapshots.put(commit.conversationId(), commit.workingState().copy());
        long next = current + 1;
        revisions.put(commit.conversationId(), next);
        return next;
    }

    public synchronized void failNextCommit() {
        this.failNextCommit = true;
    }

    public synchronized int loadCount() {
        return loads;
    }

    public synchronized List<TurnCommitCommand> commits(String conversationId) {
        return List.copyOf(turns.getOrDefault(conversationId, List.of()));
    }

    private static ConversationMessage message(
            TurnCommitCommand turn,
            String role,
            String content,
            long sequence) {
        return new ConversationMessage(
                UUID.randomUUID(),
                turn.conversationId(),
                turn.requestId(),
                sequence,
                role,
                content,
                "COMMITTED",
                1,
                Instant.EPOCH);
    }
}
