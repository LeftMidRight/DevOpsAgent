package org.example.trajectory;

import org.example.context.ContextPackage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.Usage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TrajectoryRun {

    private static final int SCHEMA_VERSION = 1;

    private final UUID runId;
    private final String conversationId;
    private final UUID requestId;
    private final String agent;
    private final JsonlTrajectoryStore store;
    private final TrajectorySanitizer sanitizer;
    private final Instant startedAt = Instant.now();
    private final AtomicLong nextSequence = new AtomicLong(1);
    private final AtomicBoolean terminal = new AtomicBoolean();

    TrajectoryRun(
            UUID runId,
            String conversationId,
            UUID requestId,
            String agent,
            JsonlTrajectoryStore store,
            TrajectorySanitizer sanitizer) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.agent = agent;
        this.store = store;
        this.sanitizer = sanitizer;
    }

    public UUID runId() {
        return runId;
    }

    void start(String taskMode, String transport) {
        append("run.started", Map.of(
                "taskMode", taskMode,
                "transport", transport,
                "status", "RUNNING"));
    }

    void recordUserMessage(String question) {
        append("message.user", Map.of(
                "role", "user",
                "content", sanitizer.truncate(question)));
    }

    public void recordContext(ContextPackage context) {
        append("context.assembled", Map.of(
                "estimatedTokens", context.estimatedTokens(),
                "retainedMessageCount", context.retainedMessageCount(),
                "summaryUntilSequence", context.summaryUntilSequence()));
    }

    public TrajectoryModelHook captureHook(List<Message> initialMessages) {
        return new TrajectoryModelHook(this, initialMessages);
    }

    synchronized void recordMessage(Message message, String phase) {
        if (message.getMessageType() == MessageType.SYSTEM) {
            return;
        }
        if (message instanceof AssistantMessage assistantMessage) {
            recordAssistantMessage(assistantMessage, phase);
            return;
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            recordToolResults(toolResponseMessage, phase);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.getMessageType().getValue());
        payload.put("content", sanitizer.truncate(message.getText()));
        payload.put("phase", phase);
        payload.put("metadata", sanitizer.sanitize(message.getMetadata()));
        append("message." + message.getMessageType().getValue(), payload);
    }

    public void recordUsage(String node, String outputType, Usage usage) {
        if (usage == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node", node);
        payload.put("outputType", outputType);
        payload.put("promptTokens", usage.getPromptTokens());
        payload.put("completionTokens", usage.getCompletionTokens());
        payload.put("totalTokens", usage.getTotalTokens());
        append("model.usage", payload);
    }

    public void complete(String answer) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        append("run.completed", Map.of(
                "status", "COMPLETED",
                "durationMs", elapsedMillis(),
                "answer", sanitizer.truncate(answer)));
    }

    public void fail(Throwable error) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "FAILED");
        payload.put("durationMs", elapsedMillis());
        payload.put("errorType", error == null ? "unknown" : error.getClass().getName());
        payload.put("message", error == null ? "unknown error" : sanitizer.truncate(error.getMessage()));
        append("run.failed", payload);
    }

    private void recordAssistantMessage(AssistantMessage message, String phase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", "assistant");
        payload.put("content", sanitizer.truncate(message.getText()));
        payload.put("phase", phase);
        payload.put("metadata", sanitizer.sanitize(message.getMetadata()));

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", toolCall.id());
            call.put("type", toolCall.type());
            call.put("name", toolCall.name());
            call.put("arguments", sanitizer.parseAndSanitize(toolCall.arguments()));
            toolCalls.add(call);
        }
        payload.put("toolCalls", toolCalls);
        append("message.assistant", payload);
    }

    private void recordToolResults(ToolResponseMessage message, String phase) {
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", "tool");
            payload.put("toolCallId", response.id());
            payload.put("toolName", response.name());
            payload.put("result", sanitizer.parseAndSanitize(response.responseData()));
            payload.put("phase", phase);
            payload.put("metadata", sanitizer.sanitize(message.getMetadata()));
            append("tool.result", payload);
        }
    }

    private long elapsedMillis() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private void append(String type, Map<String, Object> payload) {
        store.append(new TrajectoryEvent(
                SCHEMA_VERSION,
                UUID.randomUUID(),
                runId,
                conversationId,
                requestId,
                nextSequence.getAndIncrement(),
                Instant.now(),
                type,
                agent,
                payload));
    }
}
