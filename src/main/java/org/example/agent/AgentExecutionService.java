package org.example.agent;

import org.springframework.ai.chat.model.ChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.example.agent.report.OpsReportService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 统一执行入口：问答与运维共用同一条生命周期——
 * 加锁 → 建轮次 → 组装上下文 → 单 Agent 执行（含工具循环）→
 * OPS 校验渲染 → 提交持久化 → 轨迹收尾。
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
    private final OpsReportService opsReportService;
    private final AgentProperties properties;
    private final ContextProperties contextProperties;
    private final TokenEstimator tokenEstimator;

    public AgentExecutionService(
            ConversationService conversationService,
            ConversationExecutionCoordinator conversationCoordinator,
            ContextAssembler contextAssembler,
            UnifiedAgentFactory agentFactory,
            TrajectoryService trajectoryService,
            OpsReportService opsReportService,
            AgentProperties properties,
            ContextProperties contextProperties,
            TokenEstimator tokenEstimator) {
        this.conversationService = conversationService;
        this.conversationCoordinator = conversationCoordinator;
        this.contextAssembler = contextAssembler;
        this.agentFactory = agentFactory;
        this.trajectoryService = trajectoryService;
        this.opsReportService = opsReportService;
        this.properties = properties;
        this.contextProperties = contextProperties;
        this.tokenEstimator = tokenEstimator;
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

                ReactAgent agent = agentFactory.createAgent(
                        policy,
                        chatModel,
                        runContext,
                        trajectoryRun.captureHook(context.messages()),
                        new BudgetEnforcementHook(runContext, tokenEstimator, messageTokenBudget()));

                ModelReply modelReply = request.streaming()
                        ? executeStreaming(agent, context.messages(), request.mode(), events, trajectoryRun, runContext)
                        : callAndGuard(agent, context.messages(), runContext);

                runContext.rejectLateResult();

                String rawAnswer = modelReply.answer();
                reasoning = modelReply.reasoning();

                String finalAnswer = rawAnswer;
                if (request.mode() == TaskMode.OPS) {
                    events.onProgress("正在校验证据并生成诊断报告");
                    finalAnswer = opsReportService.produceReport(rawAnswer, runContext, chatModel);
                    runContext.rejectLateResult();
                }

                return commitSuccess(
                        request, events, conversationId, turn, trajectoryRun, runContext, finalAnswer, reasoning);
            } catch (BudgetExceededException e) {
                if (e.allowsPartialResult() && !runContext.isTerminated() && !runContext.isDeadlineExceeded()) {
                    logger.warn("预算耗尽，交付部分结果 - runId: {}, reason: {}", trajectoryRun.runId(), e.getMessage());
                    String partial = request.mode() == TaskMode.OPS
                            ? opsReportService.produceIncomplete(
                                    "诊断预算耗尽，停止继续取证：" + e.getMessage(), null, runContext)
                            : "任务因预算限制未完成：" + e.getMessage();
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

    private ModelReply callAndGuard(ReactAgent agent, List<Message> messages, AgentRunContext runContext) throws Exception {
        var response = agent.call(messages);
        runContext.rejectLateResult();
        return new ModelReply(response.getText(), ReasoningContentExtractor.extract(response));
    }

    /**
     * 流式执行：展示增量与权威最终答案分离。工具调用前的中间说明
     * 不会拼入最终持久化答案；OPS 模式不向客户端发送未校验的正文增量。
     */
    private ModelReply executeStreaming(
            ReactAgent agent,
            List<Message> messages,
            TaskMode mode,
            AgentExecutionEvents events,
            TrajectoryRun trajectoryRun,
            AgentRunContext runContext) throws Exception {
        StreamingAnswerCollector collector = new StreamingAnswerCollector();
        Flux<NodeOutput> stream = agent.stream(messages);
        Duration remaining = runContext.remaining();
        if (remaining.isZero()) {
            throw new BudgetExceededException(
                    "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                    BudgetExceededException.Kind.DEADLINE);
        }
        try {
            stream.doOnNext(output -> {
                if (runContext.isTerminated() || runContext.isDeadlineExceeded()) {
                    return;
                }
            if (!(output instanceof StreamingOutput streamingOutput)) {
                collector.onRoundBoundary();
                return;
            }
            OutputType type = streamingOutput.getOutputType();
            Message modelMessage = streamingOutput.message();
            if (type == OutputType.AGENT_MODEL_STREAMING) {
                String chunk = modelMessage == null ? null : modelMessage.getText();
                if (chunk != null && !chunk.isEmpty()) {
                    collector.onStreamingChunk(chunk);
                    if (mode == TaskMode.CHAT) {
                        events.onContentDelta(chunk);
                    }
                }
                String reasoningChunk = ReasoningContentExtractor.extract(modelMessage);
                if (reasoningChunk != null) {
                    collector.onReasoningChunk(reasoningChunk);
                    events.onReasoningDelta(reasoningChunk);
                }
            } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                String finished = modelMessage == null ? null : modelMessage.getText();
                collector.onModelFinished(finished);
                collector.onReasoningFinished(modelMessage);
                trajectoryRun.recordUsage(output.node(), type.name(), output.tokenUsage());
            } else {
                collector.onRoundBoundary();
            }
            }).blockLast(remaining);
        } catch (RuntimeException e) {
            if (runContext.isDeadlineExceeded()
                    || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"))) {
                throw new BudgetExceededException(
                        "任务超过最大耗时 " + runContext.budget().getMaxDurationSeconds() + " 秒",
                        BudgetExceededException.Kind.DEADLINE);
            }
            throw e;
        }

        runContext.rejectLateResult();
        String authoritative = collector.authoritativeAnswer();
        if (authoritative == null || authoritative.isBlank()) {
            throw new IllegalStateException("未从框架终态提取到完整回答");
        }
        return new ModelReply(authoritative, collector.authoritativeReasoning());
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
