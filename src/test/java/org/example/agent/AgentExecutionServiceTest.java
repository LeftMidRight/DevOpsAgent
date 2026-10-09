package org.example.agent;

import org.example.conversation.RuntimeConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutionServiceTest {

    @Test
    void modelBudgetStopsAdvisorLoopInBothTransports() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            ChatModel model = mock(ChatModel.class);
            AtomicInteger rounds = new AtomicInteger();
            java.util.function.Supplier<ChatResponse> reply = () -> new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("CHECKING").toolCalls(List.of(
                            new AssistantMessage.ToolCall("tc-" + rounds.incrementAndGet(), "function", "probeTool", "{}")))
                            .build())));
            if (streaming) {
                when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(reply.get()));
            } else {
                when(model.call(any(Prompt.class))).thenAnswer(invocation -> reply.get());
            }
            AgentProperties props = new AgentProperties();
            props.getBudget().setMaxModelCalls(2);
            AgentExecutionResult result = AgentExecutionHarness.create(model, props).service.execute(
                    new AgentExecutionService.AgentExecutionRequest(
                            "probe-session", "check", streaming, AgentExecutionEvents.NOOP));
            assertEquals(2, rounds.get());
            assertTrue(result.answer().contains("模型调用次数超过上限 2"));
        }
    }

    @Test
    void cancellationDuringStreamingRejectsLaterChunksAndDoesNotCommit() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("FIRST")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("LATE"))))));
        RunCancellation cancellation = new RunCancellation();
        List<String> emitted = new ArrayList<>();
        AgentExecutionEvents events = new AgentExecutionEvents() {
            @Override
            public void onContentDelta(String chunk) {
                emitted.add(chunk);
                cancellation.cancel();
            }
        };
        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());
        BudgetExceededException error = assertThrows(BudgetExceededException.class, () -> harness.service.execute(
                new AgentExecutionService.AgentExecutionRequest(
                        "probe-session", "check", true, events, cancellation)));
        assertEquals(BudgetExceededException.Kind.CANCELLED, error.kind());
        assertEquals(List.of("FIRST"), emitted);
        assertTrue(harness.history.commits("probe-session").isEmpty());
        RuntimeConversationState state = harness.store.get("probe-session").orElseThrow();
        assertTrue(state.messages().isEmpty());
        assertTrue(state.workingState().isEmpty());
    }

    @Test
    void toolCapsBlockExtraCallsInBatchAndAcrossRoundsWithoutLeakingBetweenRequests() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            for (boolean totalLimit : List.of(false, true)) {
                ChatModel model = mock(ChatModel.class);
                AtomicInteger rounds = new AtomicInteger();
                java.util.function.Function<Prompt, ChatResponse> reply = prompt -> {
                    int round = rounds.getAndIncrement() % 3;
                    if (round == 2) {
                        ToolResponseMessage rejected = (ToolResponseMessage) prompt.getInstructions()
                                .get(prompt.getInstructions().size() - 1);
                        assertFalse(rejected.getResponses().get(0).responseData().contains("success"));
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("FINAL"))));
                    }
                    var calls = round == 0
                            ? List.of(new AssistantMessage.ToolCall("tc-1", "function", "probeTool", "{}"),
                                    new AssistantMessage.ToolCall("tc-2", "function", "probeTool", "{\"n\":2}"))
                            : List.of(new AssistantMessage.ToolCall("tc-3", "function", "probeTool", "{\"n\":3}"));
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("CHECKING").toolCalls(calls).build())));
                };
                if (streaming) {
                    when(model.stream(any(Prompt.class))).thenAnswer(invocation ->
                            Flux.just(reply.apply(invocation.getArgument(0))));
                } else {
                    when(model.call(any(Prompt.class))).thenAnswer(invocation -> reply.apply(invocation.getArgument(0)));
                }
                AgentProperties props = new AgentProperties();
                props.getBudget().setMaxCallsPerTool(totalLimit ? 6 : 2);
                props.getBudget().setMaxToolCalls(totalLimit ? 1 : 20);
                AtomicInteger toolCalls = new AtomicInteger();
                AgentExecutionHarness harness = AgentExecutionHarness.create(
                        model, props, toolCalls, new org.example.conversation.InMemoryConversationHistory(),
                        new org.example.conversation.InMemoryRuntimeConversationStore(),
                        "{\"success\":true,\"service\":\"probe\"}");

                for (int request = 0; request < 2; request++) {
                    AgentExecutionResult result = harness.service.execute(
                            new AgentExecutionService.AgentExecutionRequest(
                                    "probe-session", "check", streaming, AgentExecutionEvents.NOOP));
                    assertEquals("FINAL", result.answer());
                    assertEquals((request + 1) * (totalLimit ? 1 : 2), toolCalls.get());
                }
                assertEquals(6, rounds.get());
            }
        }
    }

    @Test
    void streamingPersistsLastModelRoundOnly() throws Exception {
        ChatModel model = mock(ChatModel.class);
        AtomicInteger rounds = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            AssistantMessage message;
            if (rounds.incrementAndGet() == 1) {
                message = AssistantMessage.builder().content("CHECKING;")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("tc-1", "function", "probeTool", "{}")))
                        .build();
            } else {
                message = new AssistantMessage("FINAL");
            }
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });

        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());
        AgentExecutionResult result = harness.service.execute(new AgentExecutionService.AgentExecutionRequest(
                "probe-session", "check service", true, AgentExecutionEvents.NOOP));

        assertEquals("FINAL", result.answer());
        assertEquals("FINAL", harness.history.commits("probe-session").get(0).assistantContent());
        List<org.springframework.ai.chat.messages.Message> messages =
                harness.store.get("probe-session").orElseThrow().messages();
        assertEquals(List.of("check service", "FINAL"), messages.stream().map(message -> message.getText()).toList());
        assertFalse(((AssistantMessage) messages.get(1)).hasToolCalls());
        assertEquals(2, rounds.get());
    }

    @Test
    void streamingEmitsBeforeCompletionAndAggregatesTextReasoningAndUsage() throws Exception {
        ChatModel model = mock(ChatModel.class);
        List<String> emitted = new ArrayList<>();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("FI").properties(Map.of("reasoningContent", "think ")).build())))),
                Flux.defer(() -> {
                    assertEquals(List.of("FI"), emitted, "first chunk must arrive before the next is produced");
                    return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("NAL").properties(Map.of("reasoningContent", "think done")).build()))));
                }),
                Flux.just(new ChatResponse(List.of(), ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(10, 5, 15)).build()))));
        AgentExecutionEvents events = new AgentExecutionEvents() {
            @Override
            public void onContentDelta(String chunk) {
                emitted.add(chunk);
            }
        };

        AgentExecutionHarness harness = AgentExecutionHarness.create(model, new AgentProperties());
        AgentExecutionResult result = harness.service.execute(
                new AgentExecutionService.AgentExecutionRequest("probe-session", "check", true, events));

        assertEquals(List.of("FI", "NAL"), emitted);
        assertEquals("FINAL", result.answer());
        assertEquals("think done", result.reasoning());
        assertEquals("FINAL", harness.history.commits("probe-session").get(0).assistantContent());
    }

    @Test
    void lateModelResultIsNotCommitted() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1200);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("LATE_FINAL"))));
        });

        AgentProperties props = new AgentProperties();
        props.getBudget().setMaxDurationSeconds(1);
        AgentExecutionHarness harness = AgentExecutionHarness.create(model, props);

        assertThrows(BudgetExceededException.class, () -> harness.service.execute(
                new AgentExecutionService.AgentExecutionRequest(
                        "probe-session", "slow check", false, AgentExecutionEvents.NOOP)));
        assertTrue(harness.history.commits("probe-session").isEmpty());
        assertTrue(harness.store.get("probe-session").orElseThrow().workingState().isEmpty());
    }
}
