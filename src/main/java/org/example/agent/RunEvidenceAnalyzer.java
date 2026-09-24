package org.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 分析真实工具返回内容：判定有效/空/失败状态，提取服务名与告警名，
 * 并提供面向模型输入的受控截断。所有方法为纯函数，便于测试。
 */
public final class RunEvidenceAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(RunEvidenceAnalyzer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** 截断后追加的标记，模型与读者都能识别内容不完整 */
    public static final String TRUNCATION_MARK = "\n...[内容已截断，完整结果见证据记录]";

    public record EvidenceSlice(
            EvidenceStatus status,
            String service,
            String alertName,
            String content
    ) {
    }

    private RunEvidenceAnalyzer() {
    }

    /**
     * 判定工具返回内容的有效性。失败、空结果与损坏 JSON 不得伪装成有效数据。
     */
    public static EvidenceStatus classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return EvidenceStatus.EMPTY;
        }
        String trimmed = raw.trim();
        boolean looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        JsonNode root = tryParse(trimmed);
        if (looksLikeJson && root == null) {
            return EvidenceStatus.ERROR;
        }
        if (root == null) {
            return EvidenceStatus.SUCCESS;
        }
        if (root.isArray()) {
            return root.isEmpty() ? EvidenceStatus.EMPTY : EvidenceStatus.SUCCESS;
        }
        JsonNode isError = root.get("isError");
        if (isError != null && isError.isBoolean() && isError.asBoolean()) {
            return EvidenceStatus.ERROR;
        }
        JsonNode success = root.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            return EvidenceStatus.ERROR;
        }
        JsonNode status = root.get("status");
        if (status != null && status.isTextual()) {
            String value = status.asText().toLowerCase();
            if (value.equals("error") || value.equals("failed")) {
                return EvidenceStatus.ERROR;
            }
            if (value.equals("no_results") || value.equals("empty")) {
                return EvidenceStatus.EMPTY;
            }
        }
        for (String arrayField : new String[]{"alerts", "logs", "topics", "data", "results"}) {
            JsonNode array = root.get(arrayField);
            if (array != null && array.isArray() && array.isEmpty()) {
                return EvidenceStatus.EMPTY;
            }
        }
        return EvidenceStatus.SUCCESS;
    }

    /**
     * 将一次工具返回拆成可独立归属的证据切片。多条告警/日志各自保留服务与告警名。
     */
    public static List<EvidenceSlice> slices(String raw) {
        EvidenceStatus status = classify(raw);
        String safe = raw == null ? "" : raw;
        if (status != EvidenceStatus.SUCCESS) {
            return List.of(new EvidenceSlice(status, extractService(raw), extractAlertName(raw), safe));
        }
        JsonNode root = tryParse(safe);
        if (root != null && root.isObject()) {
            for (String field : new String[]{"alerts", "logs", "results"}) {
                JsonNode array = root.get(field);
                if (array != null && array.isArray() && array.size() > 1) {
                    List<EvidenceSlice> items = new ArrayList<>();
                    for (JsonNode item : array) {
                        String itemJson = item.toString();
                        EvidenceStatus itemStatus = classify(itemJson);
                        if (itemStatus == EvidenceStatus.SUCCESS && item.isValueNode()) {
                            itemStatus = EvidenceStatus.SUCCESS;
                        }
                        items.add(new EvidenceSlice(
                                itemStatus,
                                extractService(itemJson),
                                extractAlertName(itemJson),
                                itemJson));
                    }
                    return items;
                }
            }
        }
        return List.of(new EvidenceSlice(status, extractService(raw), extractAlertName(raw), safe));
    }

    public static String normalizeArguments(String toolInput) {
        if (toolInput == null) {
            return "";
        }
        String trimmed = toolInput.strip();
        JsonNode node = tryParse(trimmed);
        if (node != null) {
            try {
                return CANONICAL.writeValueAsString(CANONICAL.treeToValue(node, Object.class));
            } catch (Exception ignored) {
                // fall through to truncated raw text
            }
        }
        return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
    }

    /** 从 JSON 结果中递归提取第一个服务名字段；未识别返回 null（保留为未知） */
    public static String extractService(String raw) {
        JsonNode root = tryParse(raw);
        if (root == null) {
            return null;
        }
        return findFirstText(root, "service", "serviceName", "service_name");
    }

    /** 从 JSON 结果中递归提取第一个告警名字段；未识别返回 null（保留为未知） */
    public static String extractAlertName(String raw) {
        JsonNode root = tryParse(raw);
        if (root == null) {
            return null;
        }
        return findFirstText(root, "alert_name", "alertName", "alertname");
    }

    /**
     * 面向模型输入的受控截断，保留截断标记。
     */
    public static String truncateForModel(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        if (maxChars <= 0 || raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, maxChars) + TRUNCATION_MARK;
    }

    /** 证据记录用截断（不加提示模型的标记，仅限制存储长度） */
    public static String truncateForRecord(String raw, int maxChars) {
        if (raw == null) {
            return "";
        }
        if (maxChars <= 0 || raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, maxChars) + "...[truncated]";
    }

    public static boolean isTruncated(String raw, int maxChars) {
        return raw != null && maxChars > 0 && raw.length() > maxChars;
    }

    private static JsonNode tryParse(String raw) {
        try {
            String trimmed = raw.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                return null;
            }
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            logger.debug("工具结果 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static String findFirstText(JsonNode node, String... fieldNames) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                for (String name : fieldNames) {
                    if (entry.getKey().equalsIgnoreCase(name) && entry.getValue().isTextual()
                            && !entry.getValue().asText().isBlank()) {
                        return entry.getValue().asText();
                    }
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                String found = findFirstText(fields.next().getValue(), fieldNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String found = findFirstText(child, fieldNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
