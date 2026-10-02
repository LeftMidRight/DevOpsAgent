package org.example.agent;

/**
 * 一轮任务执行过程中的实时事件回调。同步实现可全部忽略；
 * SSE 实现将其转发给客户端。实现必须容忍在任意线程被调用。
 */
public interface AgentExecutionEvents {

    AgentExecutionEvents NOOP = new AgentExecutionEvents() {
    };

    /** 执行进度，例如"正在调用工具 queryPrometheusAlerts" */
    default void onProgress(String message) {
    }

    /** CHAT 模式流式内容增量（仅展示用，不是最终持久化答案） */
    default void onContentDelta(String chunk) {
    }

    /** 模型推理/思考过程增量（展示用，不参与最终答案持久化） */
    default void onReasoningDelta(String chunk) {
    }

    /** 本轮完整推理过程（流式结束前发送，便于客户端对齐终态） */
    default void onReasoningFinal(String reasoning) {
    }

    /** 会话已创建后立即发送，失败路径也能让调用方拿到 sessionId */
    default void onMeta(String sessionId, String requestId, String runId, String mode) {
    }

    /** 权威最终答案，用于替换前端过程文本 */
    default void onFinalAnswer(String answer) {
    }
}
