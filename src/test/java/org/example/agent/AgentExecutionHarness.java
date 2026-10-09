package org.example.agent;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import org.example.observability.langfuse.AgentRunObservation;
import org.example.observability.langfuse.LangfuseProperties;
import org.example.context.ContextProperties;
import org.example.context.TokenEstimator;
import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationHydrator;
import org.example.conversation.ConversationRepository;
import org.example.conversation.ConversationService;
import org.example.conversation.InMemoryConversationHistory;
import org.example.conversation.InMemoryRuntimeConversationStore;
import org.example.conversation.SuperBizChatMemory;
import org.example.conversation.TurnCommitService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AgentExecutionHarness {

    final AgentExecutionService service;
    final InMemoryRuntimeConversationStore store;
    final InMemoryConversationHistory history;
    final ConversationRepository repository;
    final AtomicInteger toolCalls;

    private AgentExecutionHarness(
            AgentExecutionService service,
            InMemoryRuntimeConversationStore store,
            InMemoryConversationHistory history,
            AtomicInteger toolCalls,
            ConversationRepository repository) {
        this.service = service;
        this.store = store;
        this.history = history;
        this.toolCalls = toolCalls;
        this.repository = repository;
    }

    static AgentExecutionHarness create(ChatModel model, AgentProperties properties) {
        return create(model, properties, ObservationRegistry.NOOP);
    }

    static AgentExecutionHarness create(ChatModel model, AgentProperties properties, ObservationRegistry registry) {
        return create(model, properties, new AtomicInteger(), new InMemoryConversationHistory(),
                new InMemoryRuntimeConversationStore(), "{\"success\":true,\"service\":\"probe\"}", registry);
    }

    static AgentExecutionHarness create(
            ChatModel model,
            AgentProperties properties,
            AtomicInteger toolCalls,
            InMemoryConversationHistory history,
            InMemoryRuntimeConversationStore store,
            String toolResult) {
        return create(model, properties, toolCalls, history, store, toolResult, ObservationRegistry.NOOP);
    }

    static AgentExecutionHarness create(
            ChatModel model,
            AgentProperties properties,
            AtomicInteger toolCalls,
            InMemoryConversationHistory history,
            InMemoryRuntimeConversationStore store,
            String toolResult,
            ObservationRegistry registry) {
        lenient().when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("offline-probe").build());
        UnifiedAgentFactory factory = new UnifiedAgentFactory(
                properties, null, null, null, null, null, null,
                ObservationRegistry.NOOP, Metrics.globalRegistry) {
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
                        return toolResult;
                    }
                };
                return new PreparedAgent(
                        chatModel,
                        policy.systemPrompt(),
                        OpenAiChatOptions.builder()
                                .model("offline-probe")
                                .toolCallbacks(List.of(new EvidenceCollectingToolCallback(
                                        tool, run, "local", registry)))
                                .build());
            }
        };
        ConversationHydrator hydrator = new ConversationHydrator(history, store);
        ConversationRepository repository = mock(ConversationRepository.class);
        AgentExecutionService service = new AgentExecutionService(
                new ConversationService(repository, store),
                new ConversationExecutionCoordinator(),
                hydrator,
                new TurnCommitService(history),
                new SuperBizChatMemory(store),
                factory,
                properties,
                new ContextProperties(),
                new TokenEstimator(),
                registry,
                new AgentRunObservation(registry, new LangfuseProperties(), properties));
        return new AgentExecutionHarness(service, store, history, toolCalls, repository);
    }
}
