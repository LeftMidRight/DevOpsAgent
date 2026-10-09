package org.example.agent;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.example.context.ContextProperties;
import org.example.observability.langfuse.AgentRunObservation;
import org.example.context.TokenEstimator;
import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationHydrator;
import org.example.conversation.ConversationLockTimeoutException;
import org.example.conversation.ConversationService;
import org.example.conversation.PendingTurn;
import org.example.conversation.RuntimeConversationState;
import org.example.conversation.SuperBizChatMemory;
import org.example.conversation.TurnCommitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 统一执行入口：加锁 → 取 JVM 会话状态 → 单 Agent 执行 → 成功后 Turn Commit。
 * 失败的 Run 不写入会话历史，也不提交本轮 WorkingState 草稿。
 */
@Service
public class AgentExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionService.class);

    private final ConversationService conversationService;
    private final ConversationExecutionCoordinator conversationCoordinator;
    private final ConversationHydrator conversationHydrator;
    private final TurnCommitService turnCommitService;
    private final SuperBizChatMemory chatMemory;
    private final UnifiedAgentFactory agentFactory;
    private final AgentProperties properties;
    private final ContextProperties contextProperties;
    private final TokenEstimator tokenEstimator;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final AgentRunObservation agentRunObservation;

    public AgentExecutionService(
            ConversationService conversationService,
            ConversationExecutionCoordinator conversationCoordinator,
            ConversationHydrator conversationHydrator,
            TurnCommitService turnCommitService,
            SuperBizChatMemory chatMemory,
            UnifiedAgentFactory agentFactory,
            AgentProperties properties,
            ContextProperties contextProperties,
            TokenEstimator tokenEstimator,
            ObservationRegistry observationRegistry,
            AgentRunObservation agentRunObservation) {
        this.conversationService = conversationService;
        this.conversationCoordinator = conversationCoordinator;
        this.conversationHydrator = conversationHydrator;
        this.turnCommitService = turnCommitService;
        this.chatMemory = chatMemory;
        this.agentFactory = agentFactory;
        this.properties = properties;
        this.contextProperties = contextProperties;
        this.tokenEstimator = tokenEstimator;
        this.observationRegistry = observationRegistry;
        this.agentRunObservation = agentRunObservation;
        this.toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry)
                .maxCallsPerTool(properties.getBudget().getMaxCallsPerTool())
                .maxTotalToolCalls(properties.getBudget().getMaxToolCalls())
                .onLimitExceeded(ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
    }

    public record AgentExecutionRequest(
            String requestedConversationId,
            String question,
            boolean streaming,
            AgentExecutionEvents events,
            RunCancellation cancellation
    ) {
        public AgentExecutionRequest(
                String requestedConversationId,
                String question,
                boolean streaming,
                AgentExecutionEvents events) {
            this(requestedConversationId, question, streaming, events, new RunCancellation());
        }

        public AgentExecutionRequest {
            events = events == null ? AgentExecutionEvents.NOOP : events;
            cancellation = cancellation == null ? new RunCancellation() : cancellation;
        }
    }

    public AgentExecutionResult execute(AgentExecutionRequest request) throws Exception {
        AgentExecutionEvents events = request.events();
        RunCancellation cancellation = request.cancellation();
        Instant deadline = Instant.now().plusSeconds(properties.getBudget().getMaxDurationSeconds());
        String conversationId = conversationService.resolveConversationId(request.requestedConversationId());

        try (ConversationExecutionCoordinator.Lease ignored =
                     conversationCoordinator.acquire(conversationId, deadline)) {
            RuntimeConversationState state = conversationHydrator.getActive(conversationId);
            PendingTurn pending = PendingTurn.begin(
                    UUID.randomUUID(),
                    request.question(),
                    state.workingState(),
                    state.persistedRevision());
            UUID runId = UUID.randomUUID();
            AgentRunContext runContext = new AgentRunContext(
                    runId, properties.getBudget(), events, deadline, cancellation);
            events.onMeta(conversationId, pending.requestId().toString(), runId.toString());
            Observation runObservation = agentRunObservation.start(
                    conversationId, pending.requestId().toString(), runId.toString(), request.streaming());
            try (Observation.Scope ignoredScope = runObservation.openScope()) {
                String reasoning = null;
                try {
                    events.onProgress("开始执行任务");
                    AgentTaskPolicy policy = AgentTaskPolicy.of(properties);
                    ChatModel chatModel = agentFactory.createChatModel(policy);
                    List<Message> modelMessages = modelMessages(policy, state, pending);
                    logger.info("统一执行开始 - SessionId: {}, runId: {}, 已提交消息数: {}",
                            conversationId, runId, state.messages().size());

                    PreparedAgent agent = agentFactory.createAgent(policy, chatModel, runContext);
                    BudgetEnforcementHook budgetHook = new BudgetEnforcementHook(
                            runContext, tokenEstimator, messageTokenBudget());

                    ModelReply modelReply = request.streaming()
                            ? executeStreaming(agent, modelMessages, events, runContext, budgetHook)
                            : callAndGuard(agent, modelMessages, runContext, budgetHook);

                    runContext.rejectLateResult();
                    reasoning = modelReply.reasoning();
                    AgentExecutionResult result = commitSuccess(
                            events, conversationId, state, pending, runId, runContext, modelReply.answer(), reasoning);
                    agentRunObservation.succeed(runObservation, runContext);
                    return result;
                } catch (BudgetExceededException e) {
                    if (e.allowsPartialResult() && !runContext.isTerminated() && !runContext.isDeadlineExceeded()) {
                        logger.warn("预算耗尽，交付部分结果 - runId: {}, reason: {}", runId, e.getMessage());
                        String partial = "任务因预算限制未完成：" + e.getMessage();
                        AgentExecutionResult result = commitSuccess(
                                events, conversationId, state, pending, runId, runContext, partial, reasoning);
                        agentRunObservation.partial(runObservation, runContext, e.getMessage());
                        return result;
                    }
                    runContext.terminate();
                    agentRunObservation.fail(runObservation, runContext, e);
                    throw e;
                } catch (Exception e) {
                    runContext.terminate();
                    agentRunObservation.fail(runObservation, runContext, e);
                    throw e;
                }
            } finally {
                runObservation.stop();
            }
        } catch (ConversationLockTimeoutException e) {
            throw new BudgetExceededException(e.getMessage(), BudgetExceededException.Kind.DEADLINE);
        }
    }

    private List<Message> modelMessages(
            AgentTaskPolicy policy,
            RuntimeConversationState state,
            PendingTurn pending) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(policy.systemPrompt()));
        String workingState = pending.workingStateDraft().toPromptText();
        if (!workingState.isBlank()) {
            history.add(new UserMessage(workingState));
        }
        history.addAll(chatMemory.get(state.conversationId()));
        history.add(pending.userMessage());
        return history;
    }

    private AgentExecutionResult commitSuccess(
            AgentExecutionEvents events,
            String conversationId,
            RuntimeConversationState state,
            PendingTurn pending,
            UUID runId,
            AgentRunContext runContext,
            String finalAnswer,
            String reasoning) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            throw new IllegalStateException("未从模型输出提取到完整回答");
        }
        long revision = turnCommitService.commit(conversationId, pending, finalAnswer);
        state.commitTurn(pending.userMessage(), finalAnswer, pending.workingStateDraft(), revision);
        if (reasoning != null && !reasoning.isBlank()) {
            events.onReasoningFinal(reasoning);
        }
        events.onFinalAnswer(finalAnswer);
        logger.info("统一执行完成 - SessionId: {}, runId: {}, 答案长度: {}, 推理长度: {}, 模型调用: {}, 工具调用: {}",
                conversationId, runId, finalAnswer.length(),
                reasoning == null ? 0 : reasoning.length(),
                runContext.modelCalls(), runContext.toolCalls());
        return new AgentExecutionResult(
                conversationId, pending.requestId(), runId, finalAnswer, reasoning);
    }

    private ModelReply callAndGuard(
            PreparedAgent agent,
            List<Message> messages,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook) {
        ChatResponse response = runUntilAnswer(agent, messages, runContext, budgetHook, null, null);
        runContext.rejectLateResult();
        AssistantMessage output = response.getResult().getOutput();
        return new ModelReply(output.getText(), ReasoningContentExtractor.extract(output));
    }

    /**
     * 流式执行：展示增量与权威最终答案分离。工具调用前的中间说明
     * 不会拼入最终持久化答案。
     */
    private ModelReply executeStreaming(
            PreparedAgent agent,
            List<Message> messages,
            AgentExecutionEvents events,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook) {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        ChatResponse response = runUntilAnswer(
                agent, messages, runContext, budgetHook, events, collector);
        runContext.rejectLateResult();
        String authoritative = collector.authoritativeAnswer();
        if (authoritative == null || authoritative.isBlank()) {
            throw new IllegalStateException("未从模型输出提取到完整回答");
        }
        return new ModelReply(authoritative, collector.authoritativeReasoning());
    }

    private ChatResponse runUntilAnswer(
            PreparedAgent agent,
            List<Message> contextMessages,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook,
            AgentExecutionEvents events,
            StreamingAnswerCollector collector) {
        Prompt prompt = new Prompt(contextMessages, agent.options());
        ChatClient client = ChatClient.builder(agent.chatModel(), observationRegistry, null, null)
                .defaultAdvisors(
                        ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build(),
                        new RunModelAdvisor(runContext, budgetHook, events, collector))
                .build();
        if (collector == null) {
            return client.prompt(prompt).call().chatResponse();
        }
        Duration remaining = runContext.remaining();
        if (remaining.isZero()) {
            throw new BudgetExceededException(
                    "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                    BudgetExceededException.Kind.DEADLINE);
        }
        try {
            return client.prompt(prompt).stream().chatResponse().blockLast(remaining);
        } catch (RuntimeException e) {
            if (runContext.isDeadlineExceeded()
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"))) {
                throw new BudgetExceededException(
                        "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                        BudgetExceededException.Kind.DEADLINE);
            }
            throw e;
        }
    }

    private int messageTokenBudget() {
        int budget = contextProperties.getMaxContextTokens()
                - contextProperties.getReservedOutputTokens()
                - contextProperties.getSystemAndToolsReserveTokens();
        return Math.max(1, budget);
    }

    private record ModelReply(String answer, String reasoning) {
        private ModelReply {
            if (reasoning != null && reasoning.isBlank()) {
                reasoning = null;
            }
        }
    }
}
