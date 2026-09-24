package org.example.eval;

import java.util.ArrayList;
import java.util.List;

public record AgentTrace(String scenarioId, List<ToolCall> toolCalls, long elapsedMs) {

    public record ToolCall(String tool, String detail, long elapsedMs) {}

    public static Builder builder(String scenarioId) {
        return new Builder(scenarioId);
    }

    public static final class Builder {
        private final String scenarioId;
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final long started = System.nanoTime();

        private Builder(String scenarioId) {
            this.scenarioId = scenarioId;
        }

        public Builder tool(String tool, String detail) {
            toolCalls.add(new ToolCall(tool, detail, 0));
            return this;
        }

        public AgentTrace build() {
            long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
            return new AgentTrace(scenarioId, List.copyOf(toolCalls), elapsedMs);
        }
    }
}
