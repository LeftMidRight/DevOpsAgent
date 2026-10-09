package org.example.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ObservabilityJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private ObservabilityJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (RuntimeException e) {
            return failure("serialization", "工具结果序列化失败");
        }
    }

    public static String failure(String errorType, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "query_error");
        body.put("success", false);
        body.put("errorType", errorType);
        body.put("message", message == null ? "" : message);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (RuntimeException e) {
            return "{\"status\":\"query_error\",\"success\":false,\"errorType\":\"serialization\",\"message\":\"工具结果序列化失败\"}";
        }
    }
}
