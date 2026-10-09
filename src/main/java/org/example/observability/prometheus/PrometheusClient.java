package org.example.observability.prometheus;

import org.example.observability.ObservabilityHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prometheus HTTP API。只发出 GET，不接受外部传入的任意 URL。
 */
@Component
public class PrometheusClient {

    private final PrometheusProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public PrometheusClient(PrometheusProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build());
    }

    public PrometheusClient(PrometheusProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public JsonNode alerts() {
        return get("/api/v1/alerts", Map.of());
    }

    public JsonNode targets() {
        return get("/api/v1/targets", Map.of());
    }

    public JsonNode query(String promql) {
        return get("/api/v1/query", Map.of("query", promql));
    }

    public JsonNode queryRange(String promql, Instant start, Instant end, String step) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", promql);
        query.put("start", Long.toString(start.getEpochSecond()));
        query.put("end", Long.toString(end.getEpochSecond()));
        query.put("step", step);
        return get("/api/v1/query_range", query);
    }

    private JsonNode get(String path, Map<String, String> query) {
        return ObservabilityHttp.getData(
                httpClient,
                properties.getTimeout(),
                ObservabilityHttp.uri(properties.getBaseUrl(), path, query),
                "Prometheus");
    }
}
