package org.example.agent;

import org.example.conversation.ConversationHistoryPersistence.DurableConversationRecord;
import org.example.conversation.ConversationHistoryPersistence.TurnCommitCommand;
import org.example.conversation.ConversationHydrator;
import org.example.conversation.ConversationMessage;
import org.example.conversation.InMemoryConversationHistory;
import org.example.conversation.InMemoryRuntimeConversationStore;
import org.example.conversation.PendingTurn;
import org.example.conversation.RuntimeConversationState;
import org.example.conversation.WorkingState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextManagementPhase1Test {

    @Test
    void activeSessionReadsHistoryOnceAndKeepsInheritedWorkingState() throws Exception {
        InMemoryConversationHistory history = new InMemoryConversationHistory();
        WorkingState prior = new WorkingState();
        prior.setGoal("定位 payment-service CPU 高");
        prior.knownFacts().add("CPU 92%");
        history.commitTurn(new TurnCommitCommand(
                "session-a",
                UUID.randomUUID(),
                "payment-service 为什么 CPU 高？",
                "先看 GC",
                prior,
                0));
        assertEquals(0, history.loadCount());

        List<String> prompts = new ArrayList<>();
        ChatModel model = answeringModel(prompts, "继续查 Full GC");
        InMemoryRuntimeConversationStore restarted = new InMemoryRuntimeConversationStore();
        AgentExecutionHarness harness = AgentExecutionHarness.create(
                model, new AgentProperties(), new AtomicInteger(), history, restarted, "unused");

        harness.service.execute(request("session-a", "Full GC 的根因是什么？"));

        assertEquals(1, history.loadCount());
        assertTrue(prompts.get(0).contains("CPU 92%"));
        assertTrue(prompts.get(0).contains("先看 GC"));
        assertTrue(prompts.get(0).contains("定位 payment-service CPU 高"));
        assertTrue(prompts.get(0).contains("Full GC 的根因是什么？"));
        verifyNoInteractions(harness.repository);

        prompts.clear();
        harness.service.execute(request("session-a", "还有没有别的线索？"));

        assertEquals(1, history.loadCount());
        assertTrue(prompts.get(0).contains("继续查 Full GC"));
        assertTrue(prompts.get(0).contains("CPU 92%"));
        RuntimeConversationState state = restarted.get("session-a").orElseThrow();
        assertEquals("定位 payment-service CPU 高", state.workingState().goal());
        assertEquals(List.of("CPU 92%"), state.workingState().knownFacts());
        assertEquals(3, state.turnRevision());
        assertEquals(state.turnRevision(), state.persistedRevision());
        assertEquals(6, state.messages().size());
        assertInstanceOf(UserMessage.class, state.messages().get(4));
        assertInstanceOf(AssistantMessage.class, state.messages().get(5));
    }

    @Test
    void longHistoryIsSentWholeWithoutSummaryCall() throws Exception {
        List<String> prompts = new ArrayList<>();
        String bigAnswer = "甲".repeat(9000);
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(text(invocation.getArgument(0)));
            String answer = calls.getAndIncrement() == 0 ? bigAnswer : "第二轮";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());

        harness.service.execute(request("session-long", "第一问"));
        harness.service.execute(request("session-long", "第二问"));

        assertEquals(2, calls.get());
        assertEquals(1, harness.history.loadCount());
        assertTrue(prompts.get(1).contains(bigAnswer));
        assertTrue(prompts.get(1).contains("第一问"));
        assertFalse(prompts.get(1).contains("source=\"application\""));
        verifyNoInteractions(harness.repository);
    }

    @Test
    void failedRunDiscardsWorkingStateDraftAndWritesNoHistory() throws Exception {
        WorkingState committed = new WorkingState();
        committed.setGoal("已有目标");
        PendingTurn pending = PendingTurn.begin(UUID.randomUUID(), "新问题", committed, 3);
        pending.workingStateDraft().knownFacts().add("未确认事实");
        assertEquals("已有目标", committed.goal());
        assertTrue(committed.knownFacts().isEmpty());

        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型失败"));
        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());

        assertThrows(IllegalStateException.class, () -> harness.service.execute(request("session-fail", "为什么 CPU 高？")));

        RuntimeConversationState state = harness.store.get("session-fail").orElseThrow();
        assertTrue(state.messages().isEmpty());
        assertTrue(state.workingState().isEmpty());
        assertTrue(harness.history.commits("session-fail").isEmpty());
        assertEquals(0, state.turnRevision());
    }

    @Test
    void failedRunKeepsPreviouslyCommittedState() throws Exception {
        ChatModel model = mock(ChatModel.class);
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("第一答"))));
            }
            throw new IllegalStateException("第二轮失败");
        });
        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());
        harness.service.execute(request("session-keep", "第一问"));

        assertThrows(IllegalStateException.class, () -> harness.service.execute(request("session-keep", "第二问")));

        RuntimeConversationState state = harness.store.get("session-keep").orElseThrow();
        assertEquals(List.of("第一问", "第一答"), state.messages().stream().map(Message::getText).toList());
        assertEquals("第一问", state.workingState().goal());
        assertEquals(1, harness.history.commits("session-keep").size());
        assertEquals(1, harness.history.loadCount());
    }

    @Test
    void databaseCommitFailureDoesNotPublishRuntimeState() throws Exception {
        ChatModel model = answeringModel(new ArrayList<>(), "不会留下的答案");
        InMemoryConversationHistory history = new InMemoryConversationHistory();
        history.failNextCommit();
        AgentExecutionHarness harness = AgentExecutionHarness.create(
                model, new AgentProperties(), new AtomicInteger(), history,
                new InMemoryRuntimeConversationStore(), "unused");

        assertThrows(IllegalStateException.class, () -> harness.service.execute(request("session-db", "问题")));

        RuntimeConversationState state = harness.store.get("session-db").orElseThrow();
        assertTrue(state.messages().isEmpty());
        assertTrue(state.workingState().isEmpty());
        assertTrue(history.commits("session-db").isEmpty());
    }

    @Test
    void toolResultStaysInTheRunAndSessionsDoNotShareState() throws Exception {
        List<String> prompts = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(text(invocation.getArgument(0)));
            if (round.getAndIncrement() == 0) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("先查日志")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "probeTool", "{}")))
                        .build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("日志结论"))));
        });
        AgentExecutionHarness harness = AgentExecutionHarness.create(
                model, new AgentProperties(), new AtomicInteger(), new InMemoryConversationHistory(),
                new InMemoryRuntimeConversationStore(), "RAW_TOOL_LOGS_500_LINES");

        harness.service.execute(request("session-tool", "查一下日志"));
        RuntimeConversationState toolSession = harness.store.get("session-tool").orElseThrow();
        assertEquals(List.of("查一下日志", "日志结论"), toolSession.messages().stream().map(Message::getText).toList());
        assertFalse(((AssistantMessage) toolSession.messages().get(1)).hasToolCalls());
        assertFalse(toolSession.workingState().toJson().contains("RAW_TOOL_LOGS_500_LINES"));
        assertFalse(toolSession.workingState().toJson().contains("modelCalls"));
        assertEquals("查一下日志", harness.history.commits("session-tool").get(0).userContent());
        assertEquals("日志结论", harness.history.commits("session-tool").get(0).assistantContent());

        for (Field field : RuntimeConversationState.class.getDeclaredFields()) {
            assertFalse(AgentRunContext.class.equals(field.getType()));
        }

        ChatModel other = answeringModel(new ArrayList<>(), "另一个会话");
        AgentExecutionHarness second = AgentExecutionHarness.create(
                other, new AgentProperties(), new AtomicInteger(), harness.history, harness.store, "unused");
        second.service.execute(request("session-other", "另一个问题"));
        assertFalse(harness.store.get("session-other").orElseThrow().messages().stream()
                .map(Message::getText).anyMatch("查一下日志"::equals));
        assertEquals(List.of("查一下日志", "日志结论"),
                harness.store.get("session-tool").orElseThrow().messages().stream().map(Message::getText).toList());
    }

    @Test
    void restoreSkipsIncompleteAndUncommittedMessages() {
        UUID requestId = UUID.randomUUID();
        List<ConversationMessage> rows = List.of(
                message("session-h", requestId, 1, "user", "孤儿问题", "COMMITTED"),
                message("session-h", requestId, 2, "user", "完整问题", "COMMITTED"),
                message("session-h", requestId, 3, "tool", "RAW_TOOL_LOGS_500_LINES", "COMMITTED"),
                message("session-h", requestId, 4, "assistant", "完整回答", "COMMITTED"),
                message("session-h", requestId, 5, "user", "失败问题", "FAILED"),
                message("session-h", requestId, 6, "assistant", "失败回答", "FAILED"));
        WorkingState snapshot = new WorkingState();
        snapshot.setProgress("已检查 CPU");
        RuntimeConversationState state = RuntimeConversationState.restore(
                "session-h", new DurableConversationRecord(4, snapshot, rows));

        assertEquals(List.of("完整问题", "完整回答"), state.messages().stream().map(Message::getText).toList());
        assertEquals("已检查 CPU", state.workingState().progress());
        assertEquals(4, state.turnRevision());
        assertEquals(4, state.persistedRevision());
        assertInstanceOf(UserMessage.class, state.messages().get(0));
        assertInstanceOf(AssistantMessage.class, state.messages().get(1));
    }

    @Test
    void cacheMissAfterEvictionOrExpiryReloadsTheSession() {
        MutableClock clock = new MutableClock();
        InMemoryConversationHistory history = new InMemoryConversationHistory();
        history.commitTurn(new TurnCommitCommand(
                "session-hot", UUID.randomUUID(), "热问题", "热回答", new WorkingState(), 0));
        InMemoryRuntimeConversationStore store = new InMemoryRuntimeConversationStore(
                1, Duration.ofMinutes(30), clock);
        ConversationHydrator hydrator = new ConversationHydrator(history, store);

        hydrator.getActive("session-hot");
        assertEquals(1, history.loadCount());
        clock.advance(Duration.ofMinutes(31));
        hydrator.getActive("session-hot");
        assertEquals(2, history.loadCount());
        assertEquals(List.of("热问题", "热回答"),
                store.get("session-hot").orElseThrow().messages().stream().map(Message::getText).toList());

        MutableClock boundedClock = new MutableClock();
        InMemoryRuntimeConversationStore bounded = new InMemoryRuntimeConversationStore(
                1, Duration.ofHours(1), boundedClock);
        InMemoryConversationHistory crowded = new InMemoryConversationHistory();
        crowded.commitTurn(new TurnCommitCommand(
                "session-1", UUID.randomUUID(), "一", "答一", new WorkingState(), 0));
        crowded.commitTurn(new TurnCommitCommand(
                "session-2", UUID.randomUUID(), "二", "答二", new WorkingState(), 0));
        ConversationHydrator crowdedHydrator = new ConversationHydrator(crowded, bounded);
        crowdedHydrator.getActive("session-1");
        boundedClock.advance(Duration.ofMinutes(1));
        crowdedHydrator.getActive("session-2");
        assertFalse(bounded.contains("session-1"));
        boundedClock.advance(Duration.ofMinutes(1));
        crowdedHydrator.getActive("session-1");
        assertEquals(List.of("一", "答一"),
                bounded.get("session-1").orElseThrow().messages().stream().map(Message::getText).toList());
    }

    @Test
    void workingStateJsonRoundTrips() {
        WorkingState state = new WorkingState();
        state.setGoal("定位 CPU");
        state.knownFacts().add("CPU 92%");
        state.constraints().add("不要重启生产");
        WorkingState restored = WorkingState.fromJson(state.toJson());
        assertEquals("定位 CPU", restored.goal());
        assertEquals(List.of("CPU 92%"), restored.knownFacts());
        assertEquals(List.of("不要重启生产"), restored.constraints());
        assertTrue(restored.toPromptText().contains("已确认事实"));
        assertTrue(new WorkingState().toPromptText().isEmpty());
    }

    private static AgentExecutionService.AgentExecutionRequest request(String sessionId, String question) {
        return new AgentExecutionService.AgentExecutionRequest(
                sessionId, question, false, AgentExecutionEvents.NOOP);
    }

    private static ChatModel answeringModel(List<String> prompts, String answer) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            prompts.add(text(invocation.getArgument(0)));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        return model;
    }

    private static String text(Prompt prompt) {
        StringBuilder builder = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            builder.append(message.getText()).append('\n');
        }
        return builder.toString();
    }

    private static ConversationMessage message(
            String conversationId,
            UUID requestId,
            long sequence,
            String role,
            String content,
            String status) {
        return new ConversationMessage(
                UUID.randomUUID(), conversationId, requestId, sequence, role, content, status, 1, Instant.EPOCH);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
