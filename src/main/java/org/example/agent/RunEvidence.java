package org.example.agent;

import java.time.Instant;

/**
 * 本轮任务中一次真实工具调用产生的证据记录。
 * 证据 ID 随工具结果一起提供给模型，回答只能引用这里存在的 ID。
 */
public record RunEvidence(
        String id,
        String runId,
        String toolName,
        String source,
        Instant calledAt,
        String arguments,
        EvidenceStatus status,
        String service,
        String alertName,
        String content,
        boolean truncated
) {
}
