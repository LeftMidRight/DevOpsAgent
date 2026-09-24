package org.example.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 Executor 反馈或工具原文尽量收成结构化证据。解析失败时返回空包，由校验器标成假设。
 */
@Component
public class EvidenceParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvidenceBundle parseExecutorFeedback(String raw) {
        if (raw == null || raw.isBlank()) {
            return EvidenceBundle.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            List<Evidence> items = new ArrayList<>();
            JsonNode evidenceNode = root.get("evidence");
            if (evidenceNode != null && evidenceNode.isArray()) {
                int i = 0;
                for (JsonNode node : evidenceNode) {
                    items.add(fromNode("e" + (++i), node, raw));
                }
            } else if (evidenceNode != null && evidenceNode.isTextual()) {
                items.add(Evidence.log("e1", "executor", null, null, evidenceNode.asText()));
            } else {
                items.add(Evidence.log("e1", "executor", null, null, raw));
            }
            return EvidenceBundle.of(items);
        } catch (Exception ignored) {
            return EvidenceBundle.of(Evidence.log("e1", "executor", null, null, truncate(raw)));
        }
    }

    private Evidence fromNode(String id, JsonNode node, String fallback) {
        if (node.isTextual()) {
            return Evidence.log(id, "executor", null, null, node.asText());
        }
        String type = text(node, "type");
        String source = text(node, "source");
        String service = text(node, "service");
        String alertName = text(node, "alertName");
        String content = text(node, "content");
        if (content == null) {
            content = node.toString();
        }
        EvidenceType evidenceType = parseType(type);
        return new Evidence(id, evidenceType, source == null ? "executor" : source, service, alertName, content);
    }

    private static EvidenceType parseType(String type) {
        if (type == null) {
            return EvidenceType.LOG;
        }
        return switch (type.toUpperCase()) {
            case "ALERT" -> EvidenceType.ALERT;
            case "DOCUMENT", "DOC" -> EvidenceType.DOCUMENT;
            default -> EvidenceType.LOG;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "{\"evidence\":\"" + raw.replace("\"", "'") + "\"}";
    }

    private static String truncate(String raw) {
        return raw.length() > 500 ? raw.substring(0, 500) : raw;
    }
}
