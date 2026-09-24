package org.example.trajectory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class TrajectorySanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final int MAX_COLLECTION_ITEMS = 1_000;
    private static final int MAX_DEPTH = 12;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[-_]?key|authorization|cookie|credential).*"
    );

    private final ObjectMapper objectMapper;
    private final TrajectoryProperties properties;

    public TrajectorySanitizer(ObjectMapper objectMapper, TrajectoryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Object sanitize(Object value) {
        return sanitize(value, 0, null);
    }

    public Object parseAndSanitize(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return sanitize(objectMapper.readValue(trimmed, Object.class));
            } catch (JsonProcessingException ignored) {
                // Some tool protocols use JSON-like strings. Preserve a bounded
                // representation instead of rejecting the trajectory event.
            }
        }
        return truncate(value);
    }

    private Object sanitize(Object value, int depth, String key) {
        if (key != null && SENSITIVE_KEY.matcher(key).matches()) {
            return REDACTED;
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return "[MAX_DEPTH_REACHED]";
        }
        if (value instanceof CharSequence chars) {
            return truncate(chars.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    sanitized.put("_truncated", true);
                    break;
                }
                String childKey = String.valueOf(entry.getKey());
                sanitized.put(childKey, sanitize(entry.getValue(), depth + 1, childKey));
            }
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    sanitized.add("[COLLECTION_TRUNCATED]");
                    break;
                }
                sanitized.add(sanitize(item, depth + 1, null));
            }
            return sanitized;
        }
        if (value.getClass().isArray()) {
            List<Object> sanitized = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_COLLECTION_ITEMS);
            for (int i = 0; i < length; i++) {
                sanitized.add(sanitize(Array.get(value, i), depth + 1, null));
            }
            if (Array.getLength(value) > length) {
                sanitized.add("[COLLECTION_TRUNCATED]");
            }
            return sanitized;
        }
        return truncate(String.valueOf(value));
    }

    public String truncate(String value) {
        if (value == null) {
            return "";
        }
        int maxChars = Math.max(0, properties.getMaxContentChars());
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n[TRAJECTORY_CONTENT_TRUNCATED]";
    }
}
