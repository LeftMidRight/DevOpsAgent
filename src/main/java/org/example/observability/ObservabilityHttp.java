package org.example.observability;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class ObservabilityHttp {

    private static final JsonMapper MAPPER = new JsonMapper();
    private static final int MAX_ERROR_CHARS = 300;

    private ObservabilityHttp() {
    }

    public static URI uri(String baseUrl, String path, Map<String, String> query) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringBuilder builder = new StringBuilder(base).append(path);
        if (query != null && !query.isEmpty()) {
            builder.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (!first) {
                    builder.append('&');
                }
                first = false;
                builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                builder.append('=');
                builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
        }
        return URI.create(builder.toString());
    }

    public static JsonNode getData(HttpClient httpClient, Duration timeout, URI uri, String source) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ObservabilityAccessException("interrupted", source + " 查询被中断", e);
        } catch (HttpTimeoutException e) {
            throw new ObservabilityAccessException("timeout", source + " 查询超时", e);
        } catch (IOException e) {
            throw new ObservabilityAccessException("unavailable", source + " 不可达", e);
        }
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() / 100 != 2) {
            throw new ObservabilityAccessException("http",
                    source + " 返回 HTTP " + response.statusCode() + ": " + errorText(body));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ObservabilityAccessException("upstream", source + " 返回了无法解析的响应", e);
        }
        String status = root.path("status").asText("");
        if (!"success".equals(status)) {
            String error = root.path("error").asText("");
            throw new ObservabilityAccessException("upstream",
                    source + " 查询失败" + (error.isBlank() ? "" : ": " + clip(error)));
        }
        JsonNode data = root.get("data");
        return data == null || data.isNull() ? MAPPER.createObjectNode() : data;
    }

    private static String errorText(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            String error = root.path("error").asText("");
            if (!error.isBlank()) {
                return clip(error);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体按原文截断
        }
        return clip(body);
    }

    private static String clip(String text) {
        if (text == null || text.isBlank()) {
            return "empty response";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= MAX_ERROR_CHARS ? oneLine : oneLine.substring(0, MAX_ERROR_CHARS);
    }
}
