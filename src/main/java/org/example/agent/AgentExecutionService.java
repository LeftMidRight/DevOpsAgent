package org.example.agent;

import io.micrometer.observation.ObservationRegistry;
import org.example.context.ContextAssembler;
import org.example.context.ContextPackage;
import org.example.context.ContextProperties;
import org.example.context.TokenEstimator;
import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationLockTimeoutException;
import org.example.conversation.ConversationService;
import org.example.conversation.ConversationTurn;
import org.example.trajectory.TrajectoryRun;
import org.example.trajectory.TrajectoryService;
import org.example.trajectory.TrajectoryModelHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一执行入口：
 * 加锁 → 建轮次 → 组装上下文 → 单 Agent 执行（含工具循环）→
 * 提交持久化 → 轨迹收尾。
 * 成功前发生终止时轮次标为 FAILED，不进入未来上下文。
 */
@Service
public class AgentExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionService.class);

    private final ConversationService conversationService;
    private final ConversationExecutionCoordinator conversationCoordinator;
    private final ContextAssembler contextAssembler;
    private final UnifiedAgentFactory agentFactory;
    private final TrajectoryService trajectoryService;
    private final AgentProperties properties;
    private final ContextProperties contextProperties;
    private final TokenEstimator tokenEstimator;
    private final ToolCallingManager toolCallingManager;

    public AgentExecutionService(
            ConversationService conversationService,
            ConversationExecutionCoordinator conversationCoordinator,
            ContextAssembler contextAssembler,
            UnifiedAgentFactory agentFactory,
            TrajectoryService trajectoryService,
            AgentProperties properties,
            ContextProperties contextProperties,
            TokenEstimator tokenEstimator,
            ObservationRegistry observationRegistry) {
        this.conversationService = conversationService;
        this.conversationCoordinator = conversationCoordinator;
        this.contextAssembler = contextAssembler;
        this.agentFactory = agentFactory;
        this.trajectoryService = trajectoryService;
        this.properties = properties;
        this.contextProperties = contextProperties;
        this.tokenEstimator = tokenEstimator;
        this.toolCallingManager = DefaultToolCallingManager.builder()
                .observationRegistry(observationRegistry).build();
    }

    public record AgentExecutionRequest(
            String requestedConversationId,
            String question,
            TaskMode mode,
            boolean streaming,
            AgentExecutionEvents events,
            RunCancellation cancellation
    ) {
        public AgentExecutionRequest(
                String requestedConversationId,
                String question,
                TaskMode mode,
                boolean streaming,
                AgentExecutionEvents events) {
            this(requestedConversationId, question, mode, streaming, events, new RunCancellation());
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

        try (ConversationExecutionCoordinator.Lease ignored = conversationCoordinator.acquire(conversationId, deadline)) {
            ConversationTurn turn = conversationService.beginTurn(
                    conversationId, request.question(), request.mode().name());
            TrajectoryRun trajectoryRun = trajectoryService.startRun(
                    conversationId, turn.requestId(), request.mode().name(),
                    request.streaming() ? "stream" : "sync", request.question());
            AgentRunContext runContext = new AgentRunContext(
                    trajectoryRun.runId(), request.mode(), properties.getBudget(), events, deadline, cancellation);
            events.onMeta(conversationId, turn.requestId().toString(),
                    trajectoryRun.runId().toString(), request.mode().name());

            String reasoning = null;
            try {
                events.onProgress("开始执行任务（模式: " + request.mode() + "）");
                AgentTaskPolicy policy = AgentTaskPolicy.of(request.mode(), properties);
                ChatModel chatModel = agentFactory.createChatModel(policy);

                ContextPackage context = contextAssembler.assemble(conversationId, turn.requestId(), chatModel);
                trajectoryRun.recordContext(context);
                logger.info("统一执行开始 - SessionId: {}, runId: {}, mode: {}, 消息数: {}",
                        conversationId, trajectoryRun.runId(), request.mode(), context.messages().size());

                PreparedAgent agent = agentFactory.createAgent(policy, chatModel, runContext);
                BudgetEnforcementHook budgetHook = new BudgetEnforcementHook(
                        runContext, tokenEstimator, messageTokenBudget());
                TrajectoryModelHook trajectoryHook = trajectoryRun.captureHook(context.messages());

                ModelReply modelReply = request.streaming()
                        ? executeStreaming(agent, context.messages(), events, trajectoryRun, runContext, budgetHook, trajectoryHook)
                        : callAndGuard(agent, context.messages(), trajectoryRun, runContext, budgetHook, trajectoryHook);

                runContext.rejectLateResult();

                String rawAnswer = modelReply.answer();
                reasoning = modelReply.reasoning();

                String finalAnswer = rawAnswer;

                return commitSuccess(
                        request, events, conversationId, turn, trajectoryRun, runContext, finalAnswer, reasoning);
            } catch (BudgetExceededException e) {
                if (e.allowsPartialResult() && !runContext.isTerminated() && !runContext.isDeadlineExceeded()) {
                    logger.warn("预算耗尽，交付部分结果 - runId: {}, reason: {}", trajectoryRun.runId(), e.getMessage());
                    String partial = "任务因预算限制未完成：" + e.getMessage();
                    return commitSuccess(
                            request, events, conversationId, turn, trajectoryRun, runContext, partial, reasoning);
                }
                runContext.terminate();
                failSafely(turn, trajectoryRun, e);
                throw e;
            } catch (Exception e) {
                runContext.terminate();
                failSafely(turn, trajectoryRun, e);
                throw e;
            }
        } catch (ConversationLockTimeoutException e) {
            throw new BudgetExceededException(e.getMessage(), BudgetExceededException.Kind.DEADLINE);
        }
    }

    private AgentExecutionResult commitSuccess(
            AgentExecutionRequest request,
            AgentExecutionEvents events,
            String conversationId,
            ConversationTurn turn,
            TrajectoryRun trajectoryRun,
            AgentRunContext runContext,
            String finalAnswer,
            String reasoning) {
        conversationService.completeTurn(turn, finalAnswer, request.mode().name());
        trajectoryRun.complete(finalAnswer);
        if (reasoning != null && !reasoning.isBlank()) {
            events.onReasoningFinal(reasoning);
        }
        events.onFinalAnswer(finalAnswer);
        logger.info("统一执行完成 - SessionId: {}, runId: {}, 答案长度: {}, 推理长度: {}, 模型调用: {}, 工具调用: {}",
                conversationId, trajectoryRun.runId(), finalAnswer.length(),
                reasoning == null ? 0 : reasoning.length(),
                runContext.modelCalls(), runContext.toolCalls());
        return new AgentExecutionResult(
                conversationId, turn.requestId(), trajectoryRun.runId(), request.mode(), finalAnswer, reasoning);
    }

    private ModelReply callAndGuard(
            PreparedAgent agent,
            List<Message> messages,
            TrajectoryRun trajectoryRun,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook,
            TrajectoryModelHook trajectoryHook) {
        ChatResponse response = runUntilAnswer(agent, messages, trajectoryRun, runContext, budgetHook, trajectoryHook, null, null);
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
            TrajectoryRun trajectoryRun,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook,
            TrajectoryModelHook trajectoryHook) {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        ChatResponse response = runUntilAnswer(
                agent, messages, trajectoryRun, runContext, budgetHook, trajectoryHook, events, collector);
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
            TrajectoryRun trajectoryRun,
            AgentRunContext runContext,
            BudgetEnforcementHook budgetHook,
            TrajectoryModelHook trajectoryHook,
            AgentExecutionEvents events,
            StreamingAnswerCollector collector) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(agent.systemPrompt()));
        history.addAll(contextMessages);
        Prompt prompt = new Prompt(history, agent.options());
        while (true) {
            budgetHook.beforeModel(prompt.getInstructions());
            trajectoryHook.beforeModel(prompt.getInstructions());
            ChatResponse response = collector == null
                    ? agent.chatModel().call(prompt)
                    : streamRound(agent, prompt, runContext, events, collector);
            runContext.rejectLateResult();
            trajectoryRun.recordUsage("chat", "model", response.getMetadata().getUsage());
            AssistantMessage assistant = response.getResult().getOutput();
            List<Message> afterModel = new ArrayList<>(prompt.getInstructions());
            afterModel.add(assistant);
            trajectoryHook.afterModel(afterModel);
            if (!response.hasToolCalls()) {
                return response;
            }
            ToolExecutionResult executed = toolCallingManager.executeToolCalls(prompt, response);
            prompt = new Prompt(executed.conversationHistory(), agent.options());
        }
    }

    private ChatResponse streamRound(
            PreparedAgent agent,
            Prompt prompt,
            AgentRunContext runContext,
            AgentExecutionEvents events,
            StreamingAnswerCollector collector) {
        Duration remaining = runContext.remaining();
        if (remaining.isZero()) {
            throw new BudgetExceededException(
                    "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                    BudgetExceededException.Kind.DEADLINE);
        }
        Flux<ChatResponse> stream = agent.chatModel().stream(prompt);
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        StringBuilder reasoning = new StringBuilder();
        try {
            new MessageAggregator().aggregate(stream.doOnNext(part -> {
                runContext.rejectLateResult();
                if (part.getResult() == null || part.getResult().getOutput() == null) {
                    return;
                }
                AssistantMessage message = part.getResult().getOutput();
                String chunk = message.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    collector.onStreamingChunk(chunk);
                    events.onContentDelta(chunk);
                }
                String reasoningChunk = ReasoningContentExtractor.extract(message);
                if (reasoningChunk != null) {
                    // Spring AI 2 的 SDK 返回累计推理内容；转为 SSE 增量。
                    if (reasoningChunk.startsWith(reasoning.toString())) {
                        reasoningChunk = reasoningChunk.substring(reasoning.length());
                    }
                    reasoning.append(reasoningChunk);
                    collector.onReasoningChunk(reasoningChunk);
                    events.onReasoningDelta(reasoningChunk);
                }
            }), aggregated::set).blockLast(remaining);
        } catch (RuntimeException e) {
            if (runContext.isDeadlineExceeded()
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"))) {
                throw new BudgetExceededException(
                        "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                        BudgetExceededException.Kind.DEADLINE);
            }
            throw e;
        }
        ChatResponse response = aggregated.get();
        if (response == null || response.getResult() == null) {
            throw new IllegalStateException("模型没有返回内容");
        }
        AssistantMessage finished = response.getResult().getOutput();
        if (reasoning.length() > 0) {
            var metadata = new HashMap<>(finished.getMetadata());
            metadata.put(ReasoningContentExtractor.METADATA_KEY, reasoning.toString());
            finished = AssistantMessage.builder().content(finished.getText())
                    .properties(metadata).toolCalls(finished.getToolCalls()).build();
            response = ChatResponse.builder().from(response)
                    .generations(List.of(new Generation(finished, response.getResult().getMetadata()))).build();
        }
        collector.onModelFinished(finished.getText());
        collector.onReasoningFinished(finished);
        return response;
    }

    private int messageTokenBudget() {
        int budget = contextProperties.getMaxContextTokens()
                - contextProperties.getReservedOutputTokens()
                - contextProperties.getSystemAndToolsReserveTokens();
        return Math.max(1, budget);
    }

    private void failSafely(ConversationTurn turn, TrajectoryRun trajectoryRun, Exception cause) {
        try {
            conversationService.failTurn(turn);
        } catch (Exception persistenceError) {
            logger.error("标记失败会话轮次时发生异常 - RequestId: {}", turn.requestId(), persistenceError);
            cause.addSuppressed(persistenceError);
        }
        trajectoryRun.fail(cause);
    }
}
