package org.example.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单轮任务的请求级运行状态：执行预算计数、截止时间、失败 streak、
 * 本轮证据与进度事件。所有状态归属当前请求，不跨请求共享，
 * 不放在任何共享 Service 的可变字段中。
 */
public final class AgentRunContext {

    private final UUID runId;
    private final TaskMode mode;
    private final AgentProperties.Budget budget;
    private final Instant deadline;
    private final AgentExecutionEvents events;
    private final RunCancellation cancellation;

    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> failureStreaks = new ConcurrentHashMap<>();
    private final List<RunEvidence> evidences = new CopyOnWriteArrayList<>();
    private final AtomicInteger evidenceSequence = new AtomicInteger();
    private final AtomicBoolean terminated = new AtomicBoolean();

    public AgentRunContext(UUID runId, TaskMode mode, AgentProperties.Budget budget, AgentExecutionEvents events) {
        this(runId, mode, budget, events,
                Instant.now().plus(Duration.ofSeconds(budget.getMaxDurationSeconds())),
                new RunCancellation());
    }

    public AgentRunContext(
            UUID runId,
            TaskMode mode,
            AgentProperties.Budget budget,
            AgentExecutionEvents events,
            Instant deadline,
            RunCancellation cancellation) {
        this.runId = runId;
        this.mode = mode;
        this.budget = budget;
        this.deadline = deadline;
        this.events = events == null ? AgentExecutionEvents.NOOP : events;
        this.cancellation = cancellation == null ? new RunCancellation() : cancellation;
    }

    public UUID runId() {
        return runId;
    }

    public TaskMode mode() {
        return mode;
    }

    public AgentProperties.Budget budget() {
        return budget;
    }

    public AgentExecutionEvents events() {
        return events;
    }

    public Instant deadline() {
        return deadline;
    }

    public Duration remaining() {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public boolean isTerminated() {
        return terminated.get() || cancellation.isCancelled();
    }

    /** 终止后续调度：迟到的工具结果与模型调用将被拒绝或丢弃 */
    public void terminate() {
        terminated.set(true);
        cancellation.cancel();
    }

    public boolean isDeadlineExceeded() {
        return Instant.now().isAfter(deadline);
    }

    /**
     * 模型或工具返回后、提交前再次检查。超时与取消的迟到结果不得写入会话。
     */
    public void rejectLateResult() {
        if (cancellation.isCancelled() || terminated.get()) {
            throw new BudgetExceededException("任务已取消或终止，丢弃迟到结果", BudgetExceededException.Kind.CANCELLED);
        }
        if (isDeadlineExceeded()) {
            throw new BudgetExceededException(
                    "任务超过最大耗时 " + budget.getMaxDurationSeconds() + " 秒",
                    BudgetExceededException.Kind.DEADLINE);
        }
    }

    /**
     * 在每次模型调用前检查并计数。超过预算、耗时或任务已终止时抛出异常中断循环。
     */
    public void checkModelBudget() {
        checkModelBudget(false);
    }

    public void checkModelBudget(boolean reportPhase) {
        if (cancellation.isCancelled() || terminated.get()) {
            throw new BudgetExceededException("任务已终止，停止后续模型调用", BudgetExceededException.Kind.CANCELLED);
        }
        if (isDeadlineExceeded()) {
            throw new BudgetExceededException(
                    "任务超过最大耗时 " + budget.getMaxDurationSeconds() + " 秒",
                    BudgetExceededException.Kind.DEADLINE);
        }
        int limit = budget.getMaxModelCalls();
        if (mode == TaskMode.OPS && !reportPhase) {
            limit = Math.max(1, limit - 1);
        }
        int current = modelCalls.get();
        if (current >= limit) {
            throw new BudgetExceededException(
                    "模型调用次数超过上限 " + limit, BudgetExceededException.Kind.MODEL_CALLS);
        }
        modelCalls.incrementAndGet();
    }

    /** 报告修正等额外模型调用前的预算检查（不计数，由调用方随后走 checkModelBudget） */
    public boolean canAffordModelCall() {
        return !isTerminated() && !isDeadlineExceeded()
                && modelCalls.get() < budget.getMaxModelCalls();
    }

    /**
     * 工具调用前检查并计数；返回 false 表示应拒绝本次调用。
     */
    public boolean tryAcquireToolCall() {
        if (isTerminated() || isDeadlineExceeded()) {
            return false;
        }
        return toolCalls.incrementAndGet() <= budget.getMaxToolCalls();
    }

    public int toolCalls() {
        return toolCalls.get();
    }

    public int modelCalls() {
        return modelCalls.get();
    }

    /**
     * 同一工具及相同标准化参数的连续失败/空结果是否已达上限。
     */
    public boolean isFailureStreakExceeded(String toolName, String normalizedArguments) {
        AtomicInteger streak = failureStreaks.get(streakKey(toolName, normalizedArguments));
        return streak != null && streak.get() >= budget.getMaxConsecutiveToolFailures();
    }

    public void recordToolFailure(String toolName, String normalizedArguments) {
        failureStreaks.computeIfAbsent(streakKey(toolName, normalizedArguments), k -> new AtomicInteger())
                .incrementAndGet();
    }

    public void recordToolSuccess(String toolName, String normalizedArguments) {
        failureStreaks.remove(streakKey(toolName, normalizedArguments));
    }

    /**
     * 为一次真实工具调用登记证据并分配本轮证据 ID。
     */
    public RunEvidence addEvidence(String toolName, String source, String arguments, String rawResult) {
        List<RunEvidence> added = addEvidences(toolName, source, arguments, rawResult);
        return added.get(0);
    }

    public List<RunEvidence> addEvidences(String toolName, String source, String arguments, String rawResult) {
        List<RunEvidence> added = new ArrayList<>();
        Instant calledAt = Instant.now();
        for (RunEvidenceAnalyzer.EvidenceSlice slice : RunEvidenceAnalyzer.slices(rawResult)) {
            String id = "ev-" + evidenceSequence.incrementAndGet();
            RunEvidence evidence = new RunEvidence(
                    id,
                    runId.toString(),
                    toolName,
                    source,
                    calledAt,
                    RunEvidenceAnalyzer.truncateForRecord(arguments, 500),
                    slice.status(),
                    slice.service(),
                    slice.alertName(),
                    RunEvidenceAnalyzer.truncateForRecord(slice.content(), budget.getMaxEvidenceChars()),
                    RunEvidenceAnalyzer.isTruncated(slice.content(), budget.getMaxEvidenceChars()));
            evidences.add(evidence);
            added.add(evidence);
        }
        return List.copyOf(added);
    }

    public List<RunEvidence> evidences() {
        return List.copyOf(evidences);
    }

    public RunEvidence findEvidence(String id) {
        return evidences.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    private static String streakKey(String toolName, String normalizedArguments) {
        String args = normalizedArguments == null ? "" : normalizedArguments;
        if (args.length() > 300) {
            args = args.substring(0, 300);
        }
        return toolName + "|" + args;
    }
}
