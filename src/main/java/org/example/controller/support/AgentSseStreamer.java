package org.example.controller.support;

import org.example.agent.AgentExecutionEvents;
import org.example.agent.AgentExecutionService;
import org.example.agent.RunCancellation;
import org.example.agent.TaskMode;
import org.example.dto.ChatRequest;
import org.example.dto.SseMessage;
import org.example.service.ChatApplicationService;
import org.example.dto.SseMetaPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * 将 Agent 执行事件适配为 SSE 传输，Controller 只负责创建 emitter 并委托本类。
 */
@Component
public class AgentSseStreamer {

    private static final Logger logger = LoggerFactory.getLogger(AgentSseStreamer.class);

    private final AgentExecutionService agentExecutionService;
    private final ChatApplicationService chatApplicationService;
    private final TaskExecutor streamingTaskExecutor;

    public AgentSseStreamer(
            AgentExecutionService agentExecutionService,
            ChatApplicationService chatApplicationService,
            @Qualifier("agentStreamingTaskExecutor") TaskExecutor streamingTaskExecutor) {
        this.agentExecutionService = agentExecutionService;
        this.chatApplicationService = chatApplicationService;
        this.streamingTaskExecutor = streamingTaskExecutor;
    }

    public SseEmitter startChatStream(ChatRequest request, long timeoutMs) {
        return startPrepared(timeoutMs, () -> chatApplicationService.prepareChat(request));
    }

    private SseEmitter startPrepared(long timeoutMs, Supplier<ChatApplicationService.PreparedChat> prepare) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        try {
            ChatApplicationService.PreparedChat prepared = prepare.get();
            streamingTaskExecutor.execute(() -> runStream(
                    emitter, prepared.sessionId(), prepared.question(), prepared.mode()));
        } catch (IllegalArgumentException e) {
            logger.warn("流式请求参数无效: {}", e.getMessage());
            sendAndComplete(emitter, SseMessage.error(e.getMessage()));
        }
        return emitter;
    }

    public SseEmitter startStream(String requestedId, String question, TaskMode mode, long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        streamingTaskExecutor.execute(() -> runStream(emitter, requestedId, question, mode));
        return emitter;
    }

    public void runStream(SseEmitter emitter, String requestedId, String question, TaskMode mode) {
        RunCancellation cancellation = new RunCancellation();
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(error -> cancellation.cancel());

        try {
            AgentExecutionEvents events = sseEvents(emitter, cancellation);
            agentExecutionService.execute(new AgentExecutionService.AgentExecutionRequest(
                    requestedId, question, mode, true, events, cancellation));

            send(emitter, SseMessage.done(), cancellation);
            emitter.complete();
            logger.info("流式任务完成 - mode: {}", mode);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.error("流式任务失败 - mode: {}", mode, e);
            send(emitter, SseMessage.error(failureMessage(e)), cancellation);
            emitter.completeWithError(e);
        }
    }

    public void sendAndComplete(SseEmitter emitter, SseMessage message) {
        try {
            emitter.send(event(message));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private AgentExecutionEvents sseEvents(SseEmitter emitter, RunCancellation cancellation) {
        return new AgentExecutionEvents() {
            @Override
            public void onContentDelta(String chunk) {
                send(emitter, SseMessage.content(chunk), cancellation);
            }

            @Override
            public void onProgress(String message) {
                send(emitter, SseMessage.progress(message), cancellation);
            }

            @Override
            public void onMeta(String sessionId, String requestId, String runId, String taskMode) {
                send(emitter, SseMessage.meta(new SseMetaPayload(sessionId, requestId, runId, taskMode)), cancellation);
            }

            @Override
            public void onReasoningDelta(String chunk) {
                send(emitter, SseMessage.reasoning(chunk), cancellation);
            }

            @Override
            public void onReasoningFinal(String reasoning) {
                send(emitter, SseMessage.reasoningFinal(reasoning), cancellation);
            }

            @Override
            public void onFinalAnswer(String answer) {
                send(emitter, SseMessage.finalAnswer(answer), cancellation);
            }
        };
    }

    private void send(SseEmitter emitter, SseMessage message, RunCancellation cancellation) {
        try {
            emitter.send(event(message));
        } catch (IOException e) {
            logger.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            cancellation.cancel();
        }
    }

    private static SseEmitter.SseEventBuilder event(SseMessage message) {
        return SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON);
    }

    private static String failureMessage(Exception e) {
        return "对话失败: " + e.getMessage();
    }
}
