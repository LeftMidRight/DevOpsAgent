package org.example.agent.tool;

import org.example.observability.loki.LokiClient;
import org.example.observability.loki.LokiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LokiToolsTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Test
    void queryLogsBuildsLogqlAndTrimsMessages() throws Exception {
        List<String> queries = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            queries.add(param(exchange, "query"));
            byte[] bytes = lokiBody().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            LokiProperties properties = new LokiProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTimeout(Duration.ofSeconds(2));
            properties.setMaxMessageChars(20);
            LokiTools tools = new LokiTools(new LokiClient(properties), properties);
            JsonNode body = JSON.readTree(tools.queryLogs(
                    "payment-service", "30m", "error", "Full \"GC", "payment-01", 2));
            assertEquals("success", body.path("status").asText());
            assertEquals("payment-service", body.path("service").asText());
            assertEquals("30m", body.path("range").asText());
            assertEquals(2, body.path("total").asInt());
            assertTrue(body.path("truncated").asBoolean());
            assertEquals("WARN", body.path("logs").get(0).path("level").asText());
            assertEquals("payment-01", body.path("logs").get(0).path("instance").asText());
            assertTrue(body.path("logs").get(0).path("message").asText().startsWith("Full GC pause"));
            assertTrue(body.path("logs").get(0).path("message").asText().endsWith("..."));
            assertEquals(1, queries.size());
            assertEquals("{service=\"payment-service\",level=\"ERROR\",instance=\"payment-01\"} |= \"Full \\\"GC\"",
                    queries.get(0));
            assertFalse(queries.get(0).contains("logTopic"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingServiceAndUpstreamFailureStayExplicit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "{\"status\":\"error\",\"error\":\"bad logql\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            LokiProperties properties = new LokiProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setTimeout(Duration.ofSeconds(2));
            LokiTools tools = new LokiTools(new LokiClient(properties), properties);
            JsonNode invalid = JSON.readTree(tools.queryLogs(" ", "30m", null, null, null, null));
            assertEquals("invalid_argument", invalid.path("errorType").asText());
            JsonNode failed = JSON.readTree(tools.queryLogs("payment-service", null, null, null, null, null));
            assertEquals("query_error", failed.path("status").asText());
            assertTrue(failed.path("message").asText().contains("HTTP 400"));
        } finally {
            server.stop(0);
        }
    }

    private static String lokiBody() {
        return """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"service":"payment-service","level":"WARN","instance":"payment-01"},
                   "values":[
                     ["1696665663000000000","{\\"level\\":\\"WARN\\",\\"message\\":\\"Full GC pause 1.82s, Old Gen 91%\\"}"],
                     ["1696665800000000000","{\\"level\\":\\"WARN\\",\\"message\\":\\"Full GC pause 2.01s, Old Gen 93%\\"}"]
                   ]}
                ]}}
                """;
    }

    private static String param(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
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
}
