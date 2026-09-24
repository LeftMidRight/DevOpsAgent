package org.example.agent;

/**
 * 单轮任务的运行预算（模型调用、工具调用、总耗时、上下文）耗尽或任务被终止时抛出。
 */
public class BudgetExceededException extends RuntimeException {

    public enum Kind {
        DEADLINE,
        CANCELLED,
        MODEL_CALLS,
        TOOL_CALLS,
        CONTEXT_TOKENS
    }

    private final Kind kind;

    public BudgetExceededException(String message) {
        this(message, Kind.MODEL_CALLS);
    }

    public BudgetExceededException(String message, Kind kind) {
        super(message);
        this.kind = kind == null ? Kind.MODEL_CALLS : kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 预算耗尽可交付部分结果；超时与取消必须终止且不提交成功答案 */
    public boolean allowsPartialResult() {
        return kind == Kind.MODEL_CALLS || kind == Kind.TOOL_CALLS || kind == Kind.CONTEXT_TOKENS;
    }
}
