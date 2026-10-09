package org.example.observability.loki;

import org.example.observability.ObservabilityHttp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loki query_range。只发出 GET，LogQL 由调用方用已校验参数生成。
 */
@Component
public class LokiClient {

    private final LokiProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public LokiClient(LokiProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build());
    }

    public LokiClient(LokiProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public List<LokiLine> queryRange(String logql, Instant start, Instant end, int limit) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("query", logql);
        query.put("start", start.toString());
        query.put("end", end.toString());
        query.put("limit", Integer.toString(limit));
        query.put("direction", "backward");
        JsonNode data = ObservabilityHttp.getData(
                httpClient,
                properties.getTimeout(),
                ObservabilityHttp.uri(properties.getBaseUrl(), "/loki/api/v1/query_range", query),
                "Loki");
        return lines(data);
    }

    private static List<LokiLine> lines(JsonNode data) {
        List<LokiLine> lines = new ArrayList<>();
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            return lines;
        }
        for (JsonNode stream : result) {
            Map<String, String> labels = new LinkedHashMap<>();
            JsonNode streamLabels = stream.path("stream");
            if (streamLabels.isObject()) {
                streamLabels.properties().forEach(entry -> {
                    if (entry.getValue().isValueNode()) {
                        labels.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            JsonNode values = stream.path("values");
            if (!values.isArray()) {
                continue;
            }
            for (JsonNode point : values) {
                if (!point.isArray() || point.size() < 2) {
                    continue;
                }
                lines.add(new LokiLine(point.get(0).asText(""), Map.copyOf(labels), point.get(1).asText("")));
            }
        }
        return lines;
    }

    public record LokiLine(String timestamp, Map<String, String> labels, String line) {
    }
}
