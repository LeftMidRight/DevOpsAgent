package org.example.agent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.micrometer.observation.ObservationRegistry;
import org.example.context.ContextAssembler;
import org.example.context.ContextPackage;
import org.example.context.ContextProperties;
import org.example.context.TokenEstimator;
import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationService;
import org.example.conversation.ConversationTurn;
import org.example.trajectory.JsonlTrajectoryStore;
import org.example.trajectory.TrajectoryProperties;
import org.example.trajectory.TrajectorySanitizer;
import org.example.trajectory.TrajectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentExecutionServiceTest {

    @TempDir
    Path tempDir;

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
            ConversationService conversations = mockConversations();
            when(conversations.beginTurn(anyString(), anyString())).thenReturn(
                    new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID()));
            AgentExecutionResult result = newService(model, props, conversations).execute(
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
        ConversationService conversations = mockConversations();
        ConversationTurn turn = new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID());
        when(conversations.beginTurn(anyString(), anyString())).thenReturn(turn);
        RunCancellation cancellation = new RunCancellation();
        List<String> emitted = new ArrayList<>();
        AgentExecutionEvents events = new AgentExecutionEvents() {
            @Override
            public void onContentDelta(String chunk) {
                emitted.add(chunk);
                cancellation.cancel();
            }
        };
        AgentExecutionService service = newService(model, new AgentProperties(), conversations);
        BudgetExceededException error = assertThrows(BudgetExceededException.class, () -> service.execute(
                new AgentExecutionService.AgentExecutionRequest(
                        "probe-session", "check", true, events, cancellation)));
        assertEquals(BudgetExceededException.Kind.CANCELLED, error.kind());
        assertEquals(List.of("FIRST"), emitted);
        verify(conversations, never()).completeTurn(any(), anyString());
        verify(conversations).failTurn(turn);
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
                ConversationService conversations = mockConversations();
                when(conversations.beginTurn(anyString(), anyString())).thenAnswer(invocation ->
                        new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID()));
                AtomicInteger toolCalls = new AtomicInteger();
                TrajectoryProperties trajectories = new TrajectoryProperties();
                trajectories.setEnabled(false);
                AgentExecutionService service = newService(model, props, conversations, trajectories, toolCalls);

                for (int request = 0; request < 2; request++) {
                    AgentExecutionResult result = service.execute(new AgentExecutionService.AgentExecutionRequest(
                            "probe-session", "check", streaming, AgentExecutionEvents.NOOP));
                    assertEquals("FINAL", result.answer());
                    assertEquals((request + 1) * (totalLimit ? 1 : 2), toolCalls.get());
                }
                assertEquals(6, rounds.get());
            }
        }
    }

    @Test
    void advisorRecordsEachRoundUsageAndToolEvidenceWithoutDoubleCounting() throws Exception {
        for (boolean streaming : List.of(false, true)) {
            ChatModel model = mock(ChatModel.class);
            AtomicInteger rounds = new AtomicInteger();
            java.util.function.Supplier<ChatResponse> reply = () -> {
                boolean first = rounds.getAndIncrement() == 0;
                AssistantMessage output = first
                        ? AssistantMessage.builder().content("CHECKING").toolCalls(List.of(
                                new AssistantMessage.ToolCall("tc-1", "function", "probeTool", "{}"))).build()
                        : new AssistantMessage("FINAL");
                return new ChatResponse(List.of(new Generation(output)), ChatResponseMetadata.builder()
                        .usage(first ? new DefaultUsage(10, 3, 13) : new DefaultUsage(20, 5, 25)).build());
            };
            if (streaming) {
                when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(reply.get()));
            } else {
                when(model.call(any(Prompt.class))).thenAnswer(invocation -> reply.get());
            }
            ConversationService conversations = mockConversations();
            when(conversations.beginTurn(anyString(), anyString())).thenReturn(
                    new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID()));
            TrajectoryProperties props = new TrajectoryProperties();
            props.setBaseDir(tempDir.resolve(Boolean.toString(streaming)).toString());
            AgentExecutionService service = newService(model, new AgentProperties(), conversations, props, new AtomicInteger());
            service.execute(new AgentExecutionService.AgentExecutionRequest(
                    "probe-session", "check", streaming, AgentExecutionEvents.NOOP));

            JsonMapper mapper = new JsonMapper();
            Path log = new JsonlTrajectoryStore(mapper, props).pathForConversation("probe-session");
            var events = Files.readAllLines(log).stream().map(mapper::readTree).toList();
            assertEquals(2, events.stream().filter(event -> "model.usage".equals(event.path("type").asText())).count());
            assertEquals(1, events.stream().filter(event -> "tool.result".equals(event.path("type").asText())).count());
            var totals = events.get(events.size() - 1).path("payload");
            assertEquals(2, totals.path("usageReports").asInt());
            assertEquals(30, totals.path("promptTokens").asInt());
            assertEquals(8, totals.path("completionTokens").asInt());
            assertEquals(38, totals.path("totalTokens").asInt());
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

        ConversationService conversations = mockConversations();
        ConversationTurn turn = new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID());
        when(conversations.beginTurn(anyString(), anyString())).thenReturn(turn);
        AgentExecutionService service = newService(model, new AgentProperties(), conversations);

        AgentExecutionResult result = service.execute(new AgentExecutionService.AgentExecutionRequest(
                "probe-session", "check service", true, AgentExecutionEvents.NOOP));

        assertEquals("FINAL", result.answer());
        verify(conversations).completeTurn(turn, "FINAL");
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
        ConversationService conversations = mockConversations();
        ConversationTurn turn = new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID());
        when(conversations.beginTurn(anyString(), anyString())).thenReturn(turn);
        AgentExecutionEvents events = new AgentExecutionEvents() {
            @Override
            public void onContentDelta(String chunk) {
                emitted.add(chunk);
            }
        };

        AgentExecutionResult result = newService(model, new AgentProperties(), conversations).execute(
                new AgentExecutionService.AgentExecutionRequest("probe-session", "check", true, events));

        assertEquals(List.of("FI", "NAL"), emitted);
        assertEquals("FINAL", result.answer());
        assertEquals("think done", result.reasoning());
        verify(conversations).completeTurn(turn, "FINAL");
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
        ConversationService conversations = mockConversations();
        ConversationTurn turn = new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID());
        when(conversations.beginTurn(anyString(), anyString())).thenReturn(turn);
        AgentExecutionService service = newService(model, props, conversations);

        assertThrows(BudgetExceededException.class, () -> service.execute(
                new AgentExecutionService.AgentExecutionRequest(
                        "probe-session", "slow check", false, AgentExecutionEvents.NOOP)));
        verify(conversations, never()).completeTurn(any(), anyString());
        verify(conversations).failTurn(turn);
    }

    private static ConversationService mockConversations() {
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.resolveConversationId(any())).thenReturn("probe-session");
        return conversations;
    }

    private static AgentExecutionService newService(
            ChatModel model,
            AgentProperties props,
            ConversationService conversations) {
        TrajectoryProperties trajectoryProps = new TrajectoryProperties();
        trajectoryProps.setEnabled(false);
        return newService(model, props, conversations, trajectoryProps, new AtomicInteger());
    }

    private static AgentExecutionService newService(
            ChatModel model,
            AgentProperties props,
            ConversationService conversations,
            TrajectoryProperties trajectoryProps,
            AtomicInteger toolCalls) {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("offline-probe").build());
        ContextAssembler assembler = mock(ContextAssembler.class);
        when(assembler.assemble(anyString(), any(), any())).thenReturn(
                new ContextPackage(List.of(new UserMessage("check service")), 10, 1, 0));
        UnifiedAgentFactory factory = new UnifiedAgentFactory(props, null, null, null, null, null, null,
                ObservationRegistry.NOOP, io.micrometer.core.instrument.Metrics.globalRegistry) {
            @Override
            public ChatModel createChatModel(AgentTaskPolicy policy) {
                return model;
            }

            @Override
            public PreparedAgent createAgent(
                    AgentTaskPolicy policy,
                    ChatModel chatModel,
                    AgentRunContext run) {
                ToolCallback tool = new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder().name("probeTool").description("offline probe")
                                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
                    }

                    @Override
                    public String call(String input) {
                        toolCalls.incrementAndGet();
                        return "{\"success\":true,\"service\":\"probe\"}";
                    }
                };
                return new PreparedAgent(
                        chatModel,
                        policy.systemPrompt(),
                        OpenAiChatOptions.builder()
                                .model("offline-probe")
                                .toolCallbacks(List.of(new EvidenceCollectingToolCallback(tool, run, "local")))
                                .build());
            }
        };
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        TrajectoryService trajectories = new TrajectoryService(
                new JsonlTrajectoryStore(mapper, trajectoryProps),
                new TrajectorySanitizer(mapper, trajectoryProps));
        return new AgentExecutionService(
                conversations,
                new ConversationExecutionCoordinator(),
                assembler,
                factory,
                trajectories,
                props,
                new ContextProperties(),
                new TokenEstimator(),
                ObservationRegistry.NOOP);
    }
}
