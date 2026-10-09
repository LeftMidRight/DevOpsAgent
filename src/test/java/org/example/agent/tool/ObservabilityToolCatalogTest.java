package org.example.agent.tool;

import org.example.agent.AgentProperties;
import org.example.agent.AgentTaskPolicy;
import org.example.observability.loki.LokiClient;
import org.example.observability.loki.LokiProperties;
import org.example.observability.prometheus.PrometheusClient;
import org.example.observability.prometheus.PrometheusProperties;
import org.example.observability.prometheus.PrometheusQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityToolCatalogTest {

    @Test
    void agentToolsAreDiagnosticAndDoNotExposeQueryLanguages() {
        PrometheusTools prometheus = new PrometheusTools(new PrometheusQueryService(new PrometheusClient(new PrometheusProperties())));
        LokiTools loki = new LokiTools(new LokiClient(new LokiProperties()), new LokiProperties());
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(prometheus, loki)
                .build()
                .getToolCallbacks();
        Set<String> names = Stream.of(callbacks).map(callback -> callback.getToolDefinition().name()).collect(Collectors.toSet());
        assertEquals(PrometheusTools.TOOL_NAMES.size() + 1, names.size());
        assertTrue(names.containsAll(PrometheusTools.TOOL_NAMES));
        assertTrue(names.contains(LokiTools.QUERY_LOGS));
        assertFalse(names.contains("queryPrometheusAlerts"));
        assertFalse(names.contains("getAvailableLogTopics"));
        assertFalse(names.contains("executePromql"));
        assertFalse(names.contains("executeLogql"));

        JsonMapper json = new JsonMapper();
        for (ToolCallback callback : callbacks) {
            JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());
            JsonNode properties = schema.path("properties");
            assertFalse(properties.has("promql"), callback.getToolDefinition().name());
            assertFalse(properties.has("logql"), callback.getToolDefinition().name());
            assertFalse(properties.has("metric"), callback.getToolDefinition().name());
            assertFalse(properties.has("region"), callback.getToolDefinition().name());
            assertFalse(properties.has("logTopic"), callback.getToolDefinition().name());
        }

        String prompt = AgentTaskPolicy.of(new AgentProperties()).systemPrompt();
        assertTrue(prompt.contains("queryActiveAlerts"));
        assertTrue(prompt.contains("queryLogs"));
        assertFalse(prompt.contains("queryPrometheusAlerts"));
        assertFalse(prompt.contains("getAvailableLogTopics"));
        assertTrue(prompt.contains("不要编写 PromQL 或 LogQL"));
    }
}
