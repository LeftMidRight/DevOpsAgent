package org.example.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentExecutionServiceTest {

    @Test
    void streamingPersistsLastModelRoundOnly() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("offline-probe").build());
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
        when(conversations.beginTurn(anyString(), anyString(), anyString())).thenReturn(turn);
        AgentExecutionService service = newService(model, new AgentProperties(), conversations);

        AgentExecutionResult result = service.execute(new AgentExecutionService.AgentExecutionRequest(
                "probe-session", "check service", TaskMode.CHAT, true, AgentExecutionEvents.NOOP));

        assertEquals("FINAL", result.answer());
        verify(conversations).completeTurn(turn, "FINAL", "CHAT");
        assertEquals(2, rounds.get());
    }

    @Test
    void lateModelResultIsNotCommitted() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("offline-probe").build());
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Thread.sleep(1200);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("LATE_FINAL"))));
        });

        AgentProperties props = new AgentProperties();
        props.getBudget().setMaxDurationSeconds(1);
        ConversationService conversations = mockConversations();
        ConversationTurn turn = new ConversationTurn("probe-session", UUID.randomUUID(), UUID.randomUUID());
        when(conversations.beginTurn(anyString(), anyString(), anyString())).thenReturn(turn);
        AgentExecutionService service = newService(model, props, conversations);

        assertThrows(BudgetExceededException.class, () -> service.execute(
                new AgentExecutionService.AgentExecutionRequest(
                        "probe-session", "slow check", TaskMode.CHAT, false, AgentExecutionEvents.NOOP)));
        verify(conversations, never()).completeTurn(any(), anyString(), anyString());
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
        ContextAssembler assembler = mock(ContextAssembler.class);
        when(assembler.assemble(anyString(), any(), any())).thenReturn(
                new ContextPackage(List.of(new UserMessage("check service")), 10, 1, 0));
        UnifiedAgentFactory factory = new UnifiedAgentFactory(props, null, null, null, null, null, null) {
            @Override
            public ChatModel createChatModel(AgentTaskPolicy policy) {
                return model;
            }

            @Override
            public ReactAgent createAgent(
                    AgentTaskPolicy policy,
                    ChatModel chatModel,
                    AgentRunContext run,
                    Hook... hooks) {
                ToolCallback tool = new ToolCallback() {
                    @Override
                    public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder().name("probeTool").description("offline probe")
                                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
                    }

                    @Override
                    public String call(String input) {
                        return "{\"success\":true,\"service\":\"probe\"}";
                    }
                };
                return ReactAgent.builder().name("acceptance_probe").model(chatModel)
                        .systemPrompt(policy.systemPrompt())
                        .tools(new EvidenceCollectingToolCallback(tool, run, "local"))
                        .hooks(hooks)
                        .build();
            }
        };
        TrajectoryProperties trajectoryProps = new TrajectoryProperties();
        trajectoryProps.setEnabled(false);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
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
                new TokenEstimator());
    }
}
