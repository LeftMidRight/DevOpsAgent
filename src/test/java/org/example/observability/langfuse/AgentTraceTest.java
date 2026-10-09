package org.example.observability.langfuse;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.example.agent.AgentExecutionEvents;
import org.example.agent.AgentProperties;
import org.example.agent.AgentRunContext;
import org.example.agent.EvidenceCollectingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
class AgentTraceTest {

    @Test
    void toolSpanReceivesStatusAndEvidenceIdsButNotTheRawResult() {
        ObservationRegistry registry = capturingRegistry();
        List<Observation.Context> stopped = captured(registry);
        AgentRunContext runContext = new AgentRunContext(
                UUID.randomUUID(), new AgentProperties().getBudget(), AgentExecutionEvents.NOOP);
        EvidenceCollectingToolCallback callback = new EvidenceCollectingToolCallback(
                tool("{\"service\":\"payment-service\",\"logs\":[\"secret log line\"]}"),
                runContext,
                "local",
                registry);

        Observation observation = Observation.createNotStarted(AgentToolTrace.TOOL_OBSERVATION, registry).start();
        try (Observation.Scope ignored = observation.openScope()) {
            String result = callback.call("{\"service\":\"payment-service\"}");
            assertTrue(result.contains("ev-1"));
            assertTrue(result.contains("secret log line"));
        } finally {
            observation.stop();
        }

        Observation.Context tool = stopped.get(0);
        assertEquals("SUCCESS", value(tool, "agent.tool.status"));
        assertEquals("ev-1", value(tool, "agent.tool.evidence_ids"));
        assertEquals("local", value(tool, "agent.tool.source"));
        assertTrue(Integer.parseInt(value(tool, "agent.tool.result_chars")) > 0);
        assertTrue(tool.getHighCardinalityKeyValues().stream().noneMatch(kv -> kv.getValue().contains("secret log line")));
    }

    @Test
    void enabledLangfuseWithoutEndpointFailsFast() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("langfuse.enabled", "true");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new LangfuseExportGuard(environment).validate());
        assertTrue(error.getMessage().contains("OTEL_EXPORTER_OTLP_ENDPOINT"));
    }

    private static ObservationRegistry capturingRegistry() {
        return ObservationRegistry.create();
    }

    private static List<Observation.Context> captured(ObservationRegistry registry) {
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        return stopped;
    }

    private static org.springframework.ai.tool.ToolCallback tool(String result) {
        return new org.springframework.ai.tool.ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("queryLogs").description("logs")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return result;
            }
        };
    }

    private static String value(Observation.Context context, String key) {
        KeyValue low = context.getLowCardinalityKeyValue(key);
        if (low != null) {
            return low.getValue();
        }
        return context.getHighCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key));
    }
}
