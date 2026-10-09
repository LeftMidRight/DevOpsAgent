package org.example.agent.tool;

import org.example.observability.ObservabilityAccessException;
import org.example.observability.ObservabilityJson;
import org.example.observability.ObservabilityNames;
import org.example.observability.loki.LokiClient;
import org.example.observability.loki.LokiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按服务、时间范围和可选条件查询 Loki。LogQL 不进入工具参数。
 */
@Component
public class LokiTools {

    public static final String QUERY_LOGS = "queryLogs";

    private static final Logger logger = LoggerFactory.getLogger(LokiTools.class);
    private static final JsonMapper MAPPER = new JsonMapper();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final LokiClient client;
    private final LokiProperties properties;

    public LokiTools(LokiClient client, LokiProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Tool(name = QUERY_LOGS, description = """
            Read recent application logs from Loki for one service. \
            service is required. lookback defaults to 30m and cannot exceed 6h. \
            level is optional: ERROR, WARN, INFO, DEBUG or TRACE. \
            keyword is an optional literal substring, not LogQL. \
            instance is optional. limit defaults to 20 and cannot exceed 100. Read-only.""")
    public String queryLogs(
            @ToolParam(description = "Service label, for example payment-service")
            String service,
            @ToolParam(description = "Lookback window such as 30m, 90s or 2h. Default 30m, maximum 6h.", required = false)
            String lookback,
            @ToolParam(description = "Optional log level label: ERROR, WARN, INFO, DEBUG or TRACE", required = false)
            String level,
            @ToolParam(description = "Optional literal keyword contained in the log line. Not LogQL.", required = false)
            String keyword,
            @ToolParam(description = "Optional instance label", required = false)
            String instance,
            @ToolParam(description = "Maximum number of log lines. Default 20, maximum 100.", required = false)
            Integer limit) {
        try {
            String validatedService = ObservabilityNames.requireService(service);
            ObservabilityNames.Lookback window = ObservabilityNames.lookback(lookback);
            String validatedLevel = ObservabilityNames.optionalLevel(level);
            String validatedKeyword = ObservabilityNames.optionalKeyword(keyword);
            String validatedInstance = ObservabilityNames.optionalInstance(instance);
            int resolvedLimit = resolveLimit(limit);
            String logql = logql(validatedService, validatedInstance, validatedLevel, validatedKeyword);
            Instant end = Instant.now();
            List<LokiClient.LokiLine> lines = client.queryRange(
                    logql, end.minus(window.duration()), end, resolvedLimit);
            LogQuery result = toResult(validatedService, window.label(), lines, resolvedLimit);
            logger.info("queryLogs service={} lookback={} level={} limit={} returned={}",
                    validatedService, window.label(), validatedLevel, resolvedLimit, result.total());
            return ObservabilityJson.write(result);
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("queryLogs 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }

    private int resolveLimit(Integer limit) {
        int max = Math.max(1, properties.getMaxLimit());
        int fallback = Math.min(Math.max(1, properties.getDefaultLimit()), max);
        if (limit == null) {
            return fallback;
        }
        if (limit < 1 || limit > max) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + max + " 之间");
        }
        return limit;
    }

    static String logql(String service, String instance, String level, String keyword) {
        StringBuilder query = new StringBuilder("{service=\"").append(service).append('"');
        if (level != null) {
            query.append(",level=\"").append(level).append('"');
        }
        if (instance != null) {
            query.append(",instance=\"").append(instance).append('"');
        }
        query.append('}');
        if (keyword != null) {
            query.append(" |= \"").append(ObservabilityNames.escapeLogqlLiteral(keyword)).append('"');
        }
        return query.toString();
    }

    private LogQuery toResult(String service, String range, List<LokiClient.LokiLine> lines, int limit) {
        List<LogLine> logs = new ArrayList<>();
        for (LokiClient.LokiLine line : lines) {
            logs.add(toLine(service, line));
        }
        logs.sort(Comparator.comparing(LogLine::timestamp).reversed());
        boolean truncated = lines.size() >= limit;
        if (logs.size() > limit) {
            logs = new ArrayList<>(logs.subList(0, limit));
        }
        return new LogQuery("success", service, range, logs.size(), truncated, List.copyOf(logs));
    }

    private LogLine toLine(String service, LokiClient.LokiLine line) {
        String message = line.line();
        String level = line.labels().get("level");
        String instance = line.labels().get("instance");
        JsonNode parsed = tryJson(message);
        if (parsed != null && parsed.isObject()) {
            String jsonMessage = firstText(parsed, "message", "msg");
            if (jsonMessage != null) {
                message = jsonMessage;
            }
            String jsonLevel = firstText(parsed, "level");
            if (jsonLevel != null) {
                level = jsonLevel;
            }
            String jsonInstance = firstText(parsed, "instance");
            if (jsonInstance != null) {
                instance = jsonInstance;
            }
        }
        return new LogLine(formatTimestamp(line.timestamp()), level, service, instance, clip(message));
    }

    private String clip(String message) {
        int max = Math.max(1, properties.getMaxMessageChars());
        if (message == null) {
            return "";
        }
        if (message.length() <= max) {
            return message;
        }
        return message.substring(0, max) + "...";
    }

    private static JsonNode tryJson(String line) {
        if (line == null || !line.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(line);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            long epoch = Long.parseLong(raw.trim());
            Instant instant = raw.trim().length() <= 13
                    ? Instant.ofEpochMilli(epoch)
                    : Instant.ofEpochSecond(epoch / 1_000_000_000L, epoch % 1_000_000_000L);
            return TIMESTAMP.format(instant.atZone(ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public record LogQuery(
            String status,
            String service,
            String range,
            int total,
            boolean truncated,
            List<LogLine> logs
    ) {
    }

    public record LogLine(
            String timestamp,
            String level,
            String service,
            String instance,
            String message
    ) {
    }
}
