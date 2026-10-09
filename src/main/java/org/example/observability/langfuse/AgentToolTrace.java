package org.example.observability.langfuse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * 把 Agent 已有的工具结果状态补到 Spring AI 的 spring.ai.tool span 上。
 * 不另开一条工具 span，也不写入原始结果。
 */
public final class AgentToolTrace {

    static final String TOOL_OBSERVATION = "spring.ai.tool";

    private AgentToolTrace() {
    }

    public static void record(
            ObservationRegistry registry,
            String source,
            String status,
            String evidenceIds,
            int resultChars) {
        if (registry == null) {
            return;
        }
        Observation current = registry.getCurrentObservation();
        if (current == null || !TOOL_OBSERVATION.equals(current.getContext().getName())) {
            return;
        }
        current.highCardinalityKeyValue("agent.tool.status", status == null ? "" : status);
        current.highCardinalityKeyValue("agent.tool.evidence_ids", evidenceIds == null ? "" : evidenceIds);
        current.highCardinalityKeyValue("agent.tool.result_chars", Integer.toString(Math.max(0, resultChars)));
        current.lowCardinalityKeyValue("agent.tool.source", source == null || source.isBlank() ? "unknown" : source);
    }
}
