package org.example.observability.prometheus;

import org.example.agent.tool.PrometheusTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusQueryServiceTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void queryActiveAlertsFiltersByService() throws Exception {
        try (Fixture fixture = start(this::happy)) {
            JsonNode filtered = JSON.readTree(fixture.tools.queryActiveAlerts("payment-service"));
            assertEquals("success", filtered.path("status").asText());
            assertEquals(1, filtered.path("alerts").size());
            assertEquals("HighCPUUsage", filtered.path("alerts").get(0).path("alertName").asText());
            assertEquals("payment-service", filtered.path("alerts").get(0).path("service").asText());
            assertEquals("10.0.0.12:8080", filtered.path("alerts").get(0).path("instance").asText());
            assertEquals("warning", filtered.path("alerts").get(0).path("severity").asText());
            assertEquals("firing", filtered.path("alerts").get(0).path("state").asText());
            assertEquals("CPU usage > 80%", filtered.path("alerts").get(0).path("description").asText());

            JsonNode all = JSON.readTree(fixture.tools.queryActiveAlerts(null));
            assertEquals(2, all.path("alerts").size());
        }
    }

    @Test
    void queryServiceHealthReturnsRateErrorAndLatencyTrend() throws Exception {
        try (Fixture fixture = start(this::happy)) {
            JsonNode body = JSON.readTree(fixture.tools.queryServiceHealth("payment-service", "30m", null));
            assertEquals("success", body.path("status").asText());
            assertEquals("payment-service", body.path("service").asText());
            assertEquals("30m", body.path("range").asText());
            assertEquals(120.5, body.path("requestRate").path("current").asDouble());
            assertEquals("stable", body.path("requestRate").path("trend").asText());
            assertEquals(0.083, body.path("errorRate").path("current").asDouble());
            assertEquals("rising", body.path("errorRate").path("trend").asText());
            assertEquals(1800, body.path("latencyP95Ms").path("current").asDouble());
            assertEquals(4200, body.path("latencyP99Ms").path("current").asDouble());
            assertEquals("10.0.0.12:8080", body.path("instances").get(0).path("instance").asText());
            assertTrue(body.path("instances").get(0).path("abnormal").asBoolean());
            assertTrue(fixture.queries.stream().allMatch(query -> query.contains("service=\"payment-service\"")));
            assertTrue(fixture.paths.stream().noneMatch(path -> path.contains("/api/v1/targets")));
        }
    }

    @Test
    void zeroSeriesStaysZeroAndMissingSeriesIsNoData() throws Exception {
        try (Fixture fixture = start((path, query) -> {
            if (query.contains("http_server_requests_seconds_count")
                    && !query.contains("outcome")
                    && !query.contains("by (instance)")) {
                return matrix(0, 0, 0);
            }
            return empty();
        })) {
            JsonNode body = JSON.readTree(fixture.tools.queryServiceHealth("payment-service", "30m", null));
            assertEquals("success", body.path("status").asText());
            assertEquals("ok", body.path("requestRate").path("status").asText());
            assertEquals(0, body.path("requestRate").path("current").asDouble());
            assertEquals("stable", body.path("requestRate").path("trend").asText());
            assertEquals("no_data", body.path("errorRate").path("status").asText());
            assertFalse(body.path("errorRate").has("current"));
            assertTrue(fixture.paths.stream().noneMatch(path -> path.contains("/api/v1/targets")));
        }
    }

    @Test
    void missingMetricsWithDownTargetIsTargetDown() throws Exception {
        try (Fixture fixture = start((path, query) -> path.contains("/targets") ? targets("down") : empty())) {
            JsonNode body = JSON.readTree(fixture.tools.queryServiceHealth("order-service", "30m", null));
            assertEquals("target_down", body.path("status").asText());
            assertEquals("down", body.path("targetHealth").asText());
            assertEquals("connection refused", body.path("lastError").asText());
            assertEquals("http://10.0.0.13:8080/actuator/prometheus", body.path("scrapeUrl").asText());
            assertFalse(body.has("requestRate"));
            assertFalse(body.has("errorRate"));
        }
    }

    @Test
    void missingMetricsWithUpTargetIsNoData() throws Exception {
        try (Fixture fixture = start((path, query) -> path.contains("/targets") ? targets("up") : empty())) {
            JsonNode body = JSON.readTree(fixture.tools.queryResourceUsage("order-service", null, null));
            assertEquals("no_data", body.path("status").asText());
            assertEquals("up", body.path("targetHealth").asText());
            assertFalse(body.has("processCpu"));
        }
    }

    @Test
    void queryResourceUsageAndJvmHealthReturnSummaries() throws Exception {
        try (Fixture fixture = start(this::happy)) {
            JsonNode resources = JSON.readTree(fixture.tools.queryResourceUsage("payment-service", "30m", null));
            assertEquals("success", resources.path("status").asText());
            assertEquals(0.92, resources.path("processCpu").path("current").asDouble());
            assertEquals("rising", resources.path("processCpu").path("trend").asText());
            assertEquals(1_000_000_000d, resources.path("processMemoryBytes").path("current").asDouble());
            assertEquals("10.0.0.12:8080", resources.path("instances").get(0).path("instance").asText());
            assertTrue(resources.path("instances").get(0).path("abnormal").asBoolean());

            JsonNode jvm = JSON.readTree(fixture.tools.queryJvmHealth("payment-service", "30m", null));
            assertEquals(0.86, jvm.path("heapUsage").path("current").asDouble());
            assertEquals(0.91, jvm.path("oldGenUsage").path("current").asDouble());
            assertEquals("rising", jvm.path("oldGenUsage").path("trend").asText());
            assertEquals(7.2, jvm.path("gcRate").path("current").asDouble());
            assertEquals(850, jvm.path("gcPauseP99Ms").path("current").asDouble());
            assertEquals(214, jvm.path("threads").path("current").asDouble());
            assertEquals(0.91, jvm.path("instances").get(0).path("oldGenUsage").asDouble());
        }
    }

    @Test
    void checkMonitoringTargetReportsMissingServiceLabel() throws Exception {
        try (Fixture fixture = start((path, query) -> targets("up"))) {
            JsonNode body = JSON.readTree(fixture.tools.checkMonitoringTarget("payment-service", null));
            assertEquals("no_data", body.path("status").asText());
            assertEquals("absent", body.path("targetHealth").asText());
            assertEquals(0, body.path("targets").size());
        }
    }

    @Test
    void checkMonitoringTargetReportsDownTarget() throws Exception {
        try (Fixture fixture = start((path, query) -> targets("down"))) {
            JsonNode body = JSON.readTree(fixture.tools.checkMonitoringTarget("order-service", null));
            assertEquals("success", body.path("status").asText());
            assertEquals("down", body.path("targetHealth").asText());
            assertEquals("connection refused", body.path("lastError").asText());
            assertTrue(body.path("lastScrape").asText().contains("2026-10-07"));
            assertEquals(1, body.path("targets").size());
        }
    }

    @Test
    void httpFailureIsQueryErrorAndInvalidServiceDoesNotCallPrometheus() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        try (Fixture fixture = start((path, query) -> {
            hits.incrementAndGet();
            return "{\"status\":\"error\",\"error\":\"boom\"}";
        }, 500)) {
            JsonNode invalid = JSON.readTree(fixture.tools.queryServiceHealth("pay\"}", "30m", null));
            assertEquals("query_error", invalid.path("status").asText());
            assertEquals("invalid_argument", invalid.path("errorType").asText());
            assertEquals(0, hits.get());

            JsonNode failed = JSON.readTree(fixture.tools.queryActiveAlerts(null));
            assertEquals("query_error", failed.path("status").asText());
            assertFalse(failed.path("success").asBoolean());
            assertTrue(failed.path("message").asText().contains("HTTP 500"));
        }
    }

    private String happy(String path, String query) {
        if (path.contains("/alerts")) {
            return alerts();
        }
        if (path.contains("/targets")) {
            return targets("up");
        }
        if (query.contains("by (instance)") && query.contains("outcome")) {
            return vector("10.0.0.12:8080", 0.2);
        }
        if (query.contains("outcome=\"SERVER_ERROR\"")) {
            return matrix(0.02, 0.05, 0.083);
        }
        if (query.contains("histogram_quantile(0.95")) {
            return matrix(1.2, 1.5, 1.8);
        }
        if (query.contains("histogram_quantile(0.99") && query.contains("http_server")) {
            return matrix(2.0, 3.0, 4.2);
        }
        if (query.contains("http_server_requests_seconds_count")) {
            return matrix(120, 121, 120.5);
        }
        if (query.contains("process_cpu_usage") && query.contains("by (instance)")) {
            return vector("10.0.0.12:8080", 0.92);
        }
        if (query.contains("process_cpu_usage")) {
            return matrix(0.4, 0.7, 0.92);
        }
        if (query.contains("process_resident_memory_bytes") && query.contains("by (instance)")) {
            return vector("10.0.0.12:8080", 1_000_000_000);
        }
        if (query.contains("process_resident_memory_bytes")) {
            return matrix(800_000_000, 900_000_000, 1_000_000_000);
        }
        if (query.contains("by (instance)") && query.contains("Old Gen")) {
            return vector("jvm-1", 0.91);
        }
        if (query.contains("Old Gen")) {
            return matrix(0.7, 0.8, 0.91);
        }
        if (query.contains("area=\\\"heap\\\"") || query.contains("area=\"heap\"")) {
            return matrix(0.5, 0.7, 0.86);
        }
        if (query.contains("jvm_gc_pause_seconds_bucket")) {
            return matrix(0.2, 0.5, 0.85);
        }
        if (query.contains("jvm_gc_pause_seconds_count")) {
            return matrix(2, 4, 7.2);
        }
        if (query.contains("jvm_threads_live_threads")) {
            return matrix(180, 200, 214);
        }
        return empty();
    }

    private Fixture start(Responder responder) throws IOException {
        return start(responder, 200);
    }

    private Fixture start(Responder responder, int statusCode) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = param(exchange, "query");
            paths.add(path);
            if (!query.isBlank()) {
                queries.add(query);
            }
            byte[] bytes = responder.body(path, query).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        PrometheusProperties properties = new PrometheusProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout(Duration.ofSeconds(2));
        PrometheusTools tools = new PrometheusTools(new PrometheusQueryService(new PrometheusClient(properties)));
        return new Fixture(server, paths, queries, tools);
    }

    private static String param(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return "";
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String matrix(double... values) {
        StringBuilder body = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[{\"metric\":{},\"values\":[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('[').append(1_700_000_000 + i * 60L).append(",\"").append(values[i]).append("\"]");
        }
        return body.append("]}]}}").toString();
    }

    private static String vector(String instance, double value) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"instance\":\""
                + instance + "\"},\"value\":[1700000000,\"" + value + "\"]}]}}";
    }

    private static String empty() {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}";
    }

    private static String alerts() {
        return """
                {"status":"success","data":{"alerts":[
                  {"labels":{"alertname":"HighCPUUsage","service":"payment-service","instance":"10.0.0.12:8080","severity":"warning"},
                   "annotations":{"description":"CPU usage > 80%"},"state":"firing","activeAt":"2026-10-07T07:40:00Z"},
                  {"labels":{"alertname":"SlowResponse","service":"order-service","instance":"10.0.0.13:8080","severity":"critical"},
                   "annotations":{"description":"slow"},"state":"firing","activeAt":"2026-10-07T07:50:00Z"}
                ]}}
                """;
    }

    private static String targets(String health) {
        String error = "down".equals(health) ? "connection refused" : "";
        return """
                {"status":"success","data":{"activeTargets":[{
                  "labels":{"service":"order-service","instance":"10.0.0.13:8080"},
                  "health":"%s","lastError":"%s","lastScrape":"2026-10-07T08:00:00Z",
                  "scrapeUrl":"http://10.0.0.13:8080/actuator/prometheus"
                }]}}
                """.formatted(health, error);
    }

    @FunctionalInterface
    private interface Responder {
        String body(String path, String query);
    }

    private record Fixture(HttpServer server, List<String> paths, List<String> queries, PrometheusTools tools)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}
