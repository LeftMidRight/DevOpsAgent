package org.example.observability.langfuse;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.example.agent.AgentProperties;
import org.example.agent.AgentRunContext;
import org.springframework.stereotype.Component;

/**
 * 一次 Agent Run 的根 Observation。Spring AI 的模型调用和工具调用挂在它下面。
 * 这里不决定下一步工具，也不改运行状态。
 */
@Component
public class AgentRunObservation {

    public static final String NAME = "agent.run";

    private final ObservationRegistry observationRegistry;
    private final LangfuseProperties langfuseProperties;
    private final AgentProperties agentProperties;

    public AgentRunObservation(
            ObservationRegistry observationRegistry,
            LangfuseProperties langfuseProperties,
            AgentProperties agentProperties) {
        this.observationRegistry = observationRegistry;
        this.langfuseProperties = langfuseProperties;
        this.agentProperties = agentProperties;
    }

    public Observation start(String conversationId, String requestId, String runId, boolean streaming) {
        return Observation.createNotStarted(NAME, observationRegistry)
                .contextualName("agent.run")
                .lowCardinalityKeyValue("agent.environment", bounded(langfuseProperties.getEnvironment(), "dev"))
                .lowCardinalityKeyValue("agent.streaming", Boolean.toString(streaming))
                .lowCardinalityKeyValue("agent.model", bounded(agentProperties.getModel(), "unknown"))
                .highCardinalityKeyValue("agent.run_id", text(runId))
                .highCardinalityKeyValue("agent.request_id", text(requestId))
                .highCardinalityKeyValue("agent.conversation_id", text(conversationId))
                .highCardinalityKeyValue("agent.app_version", bounded(langfuseProperties.getAppVersion(), "unknown"))
                .start();
    }

    public void succeed(Observation observation, AgentRunContext runContext) {
        finish(observation, runContext, "success", null);
    }

    public void partial(Observation observation, AgentRunContext runContext, String reason) {
        finish(observation, runContext, "partial", reason);
    }

    public void fail(Observation observation, AgentRunContext runContext, Throwable error) {
        finish(observation, runContext, "error", error == null ? null : error.getMessage());
        if (error != null) {
            observation.error(error);
        }
    }

    private static void finish(Observation observation, AgentRunContext runContext, String status, String reason) {
        observation.lowCardinalityKeyValue("agent.status", status);
        observation.highCardinalityKeyValue("agent.model_calls", Integer.toString(runContext.modelCalls()));
        observation.highCardinalityKeyValue("agent.tool_calls", Integer.toString(runContext.toolCalls()));
        if (reason != null && !reason.isBlank()) {
            observation.highCardinalityKeyValue("agent.termination_reason", clip(reason));
        }
    }

    private static String bounded(String value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : clip(text);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    static String clip(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200);
    }
}
