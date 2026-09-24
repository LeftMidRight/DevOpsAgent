package org.example.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

/**
 * 统一工具调用包装：对本地方法工具与 MCP 工具提供同一入口的
 * 预算控制、连续失败限制、结果受控截断、证据采集与进度事件。
 * 每个请求使用独立的 AgentRunContext，包装器不跨请求共享状态。
 */
public class EvidenceCollectingToolCallback implements ToolCallback {

    private static final Logger logger = LoggerFactory.getLogger(EvidenceCollectingToolCallback.class);

    private final ToolCallback delegate;
    private final AgentRunContext runContext;
    private final String source;

    public EvidenceCollectingToolCallback(ToolCallback delegate, AgentRunContext runContext, String source) {
        this.delegate = delegate;
        this.runContext = runContext;
        this.source = source;
    }

    public ToolCallback delegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return execute(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return execute(toolInput, toolContext);
    }

    private String execute(String toolInput, ToolContext toolContext) {
        String toolName = getToolDefinition().name();
        String normalizedArgs = RunEvidenceAnalyzer.normalizeArguments(toolInput);

        if (runContext.isTerminated() || runContext.isDeadlineExceeded()) {
            return rejection(toolName, "任务已终止，不再执行新的工具调用");
        }
        if (!runContext.tryAcquireToolCall()) {
            return rejection(toolName,
                    "工具调用次数或任务耗时已达上限（上限 " + runContext.budget().getMaxToolCalls()
                            + " 次），请基于已有信息作答");
        }
        if (runContext.isFailureStreakExceeded(toolName, normalizedArgs)) {
            return rejection(toolName,
                    "相同调用已连续失败或返回空结果 " + runContext.budget().getMaxConsecutiveToolFailures()
                            + " 次，请停止该方向的重复查询并在回答中说明");
        }

        runContext.events().onProgress("正在调用工具 " + toolName);
        long startNanos = System.nanoTime();
        String raw;
        try {
            raw = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            logger.warn("工具调用异常 - runId: {}, tool: {}, elapsedMs: {}", runContext.runId(), toolName, elapsedMs, e);
            runContext.recordToolFailure(toolName, normalizedArgs);
            if (runContext.isTerminated() || runContext.isDeadlineExceeded()) {
                return rejection(toolName, "任务已终止，丢弃迟到的工具异常结果");
            }
            List<RunEvidence> evidences = runContext.addEvidences(
                    toolName, source, normalizedArgs, errorJson(toolName, e.getMessage()));
            runContext.events().onProgress("工具 " + toolName + " 调用失败（" + ids(evidences) + "）");
            return withEvidenceHeader(evidences, RunEvidenceAnalyzer.truncateForModel(
                    errorJson(toolName, e.getMessage()), runContext.budget().getMaxToolResultChars()));
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        if (runContext.isTerminated() || runContext.isDeadlineExceeded()) {
            logger.info("丢弃迟到工具结果 - runId: {}, tool: {}, elapsedMs: {}",
                    runContext.runId(), toolName, elapsedMs);
            return rejection(toolName, "任务已终止，丢弃迟到的工具结果");
        }
        List<RunEvidence> evidences = runContext.addEvidences(toolName, source, normalizedArgs, raw);
        boolean allSuccess = evidences.stream().allMatch(e -> e.status() == EvidenceStatus.SUCCESS);
        if (allSuccess) {
            runContext.recordToolSuccess(toolName, normalizedArgs);
        } else {
            runContext.recordToolFailure(toolName, normalizedArgs);
        }
        logger.info("工具调用完成 - runId: {}, tool: {}, evidence: {}, elapsedMs: {}",
                runContext.runId(), toolName, ids(evidences), elapsedMs);
        runContext.events().onProgress(progressMessage(toolName, evidences, elapsedMs));

        String truncated = RunEvidenceAnalyzer.truncateForModel(raw, runContext.budget().getMaxToolResultChars());
        return withEvidenceHeader(evidences, truncated);
    }

    private static String progressMessage(String toolName, List<RunEvidence> evidences, long elapsedMs) {
        EvidenceStatus status = evidences.stream().anyMatch(e -> e.status() == EvidenceStatus.ERROR)
                ? EvidenceStatus.ERROR
                : evidences.stream().anyMatch(e -> e.status() == EvidenceStatus.EMPTY)
                ? EvidenceStatus.EMPTY
                : EvidenceStatus.SUCCESS;
        return switch (status) {
            case SUCCESS -> "工具 " + toolName + " 完成（证据 " + ids(evidences) + "，" + elapsedMs + "ms）";
            case EMPTY -> "工具 " + toolName + " 返回空结果（" + ids(evidences) + "）";
            case ERROR -> "工具 " + toolName + " 返回失败状态（" + ids(evidences) + "）";
        };
    }

    private static String withEvidenceHeader(List<RunEvidence> evidences, String content) {
        RunEvidence first = evidences.get(0);
        return "[evidence_id: " + ids(evidences) + " | tool: " + first.toolName()
                + " | status: " + first.status() + "]\n" + content;
    }

    private static String ids(List<RunEvidence> evidences) {
        return evidences.stream().map(RunEvidence::id).collect(java.util.stream.Collectors.joining(","));
    }

    private static String rejection(String toolName, String reason) {
        logger.info("工具调用被拒绝 - tool: {}, reason: {}", toolName, reason);
        return "{\"success\":false,\"error\":\"TOOL_CALL_REJECTED\",\"message\":\""
                + reason.replace("\"", "'") + "\"}";
    }

    private static String errorJson(String toolName, String errorMessage) {
        String message = errorMessage == null ? "unknown error" : errorMessage.replace("\"", "'");
        return "{\"success\":false,\"tool\":\"" + toolName + "\",\"message\":\"工具调用异常: " + message + "\"}";
    }
}
