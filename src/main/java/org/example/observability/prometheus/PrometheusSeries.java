package org.example.observability.prometheus;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PrometheusSeries {

    private PrometheusSeries() {
    }

    static List<Double> values(JsonNode data) {
        JsonNode result = data.path("result");
        if (!result.isArray() || result.isEmpty()) {
            return List.of();
        }
        return points(result.get(0));
    }

    static Map<String, Double> latestByInstance(JsonNode data) {
        Map<String, Double> values = new LinkedHashMap<>();
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            return values;
        }
        for (JsonNode series : result) {
            String instance = series.path("metric").path("instance").asText("");
            if (instance.isBlank()) {
                continue;
            }
            List<Double> points = points(series);
            if (!points.isEmpty()) {
                values.put(instance, points.get(points.size() - 1));
            }
        }
        return values;
    }

    private static List<Double> points(JsonNode series) {
        List<Double> values = new ArrayList<>();
        JsonNode range = series.get("values");
        if (range != null && range.isArray()) {
            for (JsonNode point : range) {
                add(values, point);
            }
            return values;
        }
        add(values, series.get("value"));
        return values;
    }

    private static void add(List<Double> values, JsonNode point) {
        if (point == null || !point.isArray() || point.size() < 2) {
            return;
        }
        Double value = parse(point.get(1));
        if (value != null) {
            values.add(value);
        }
    }

    private static Double parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        double value;
        if (node.isNumber()) {
            value = node.asDouble();
        } else {
            String text = node.asText();
            if (text == null || text.isBlank() || "NaN".equalsIgnoreCase(text)
                    || "+Inf".equals(text) || "-Inf".equals(text)) {
                return null;
            }
            try {
                value = Double.parseDouble(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return Double.isFinite(value) ? value : null;
    }
}
