package org.example.agent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 解析 OPS 模式 Agent 最终回答中的结构化草稿。
 * 容忍 Markdown 代码围栏与前后少量说明文字，按首个 "{" 至末个 "}" 提取。
 * 解析成功还要求 JSON 含有报告契约字段，避免把任意对象当成草稿。
 */
@Component
public class OpsReportParser {

    private static final Logger logger = LoggerFactory.getLogger(OpsReportParser.class);
    private static final Set<String> CONTRACT_FIELDS = Set.of(
            "alertSummary", "findings", "unresolvedIssues", "overallAssessment", "riskLevel");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @throws OpsReportParseException 内容中不存在可解析的草稿 JSON
     */
    public OpsReportDraft parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new OpsReportParseException("OPS 最终回答为空，无法解析结构化草稿");
        }
        String candidate = extractJsonObject(raw);
        try {
            JsonNode node = objectMapper.readTree(candidate);
            if (node == null || !node.isObject() || !hasContractField(node)) {
                throw new OpsReportParseException("JSON 缺少报告契约字段（alertSummary/findings/unresolvedIssues/overallAssessment/riskLevel）");
            }
            return objectMapper.treeToValue(node, OpsReportDraft.class);
        } catch (OpsReportParseException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("OPS 草稿 JSON 解析失败: {}", e.getMessage());
            throw new OpsReportParseException("OPS 草稿 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    private static boolean hasContractField(JsonNode node) {
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (CONTRACT_FIELDS.contains(names.next())) {
                return true;
            }
        }
        return false;
    }

    static String extractJsonObject(String raw) {
        String text = raw.trim();
        // 去除可能的 Markdown 代码围栏
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new OpsReportParseException("OPS 最终回答中未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    public static class OpsReportParseException extends RuntimeException {
        public OpsReportParseException(String message) {
            super(message);
        }

        public OpsReportParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
