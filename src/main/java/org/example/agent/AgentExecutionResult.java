package org.example.agent;

import java.util.UUID;

/**
 * 一轮任务执行的权威结果。answer 为最终持久化与交付的内容：
 * CHAT 为最终回答；OPS 为校验后渲染的报告。
 */
public record AgentExecutionResult(
        String conversationId,
        UUID requestId,
        UUID runId,
        TaskMode mode,
        String answer,
        String reasoning
) {
}
