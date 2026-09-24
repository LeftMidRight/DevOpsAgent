package org.example.agent.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OPS 模式最终回答的结构化草稿。由 Agent 按输出契约产生，
 * 服务端解析、校验后再渲染为 Markdown 交付，正文与结论不分别生成。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpsReportDraft(
        List<AlertSummaryItem> alertSummary,
        List<Finding> findings,
        List<String> unresolvedIssues,
        String overallAssessment,
        String riskLevel
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AlertSummaryItem(
            String alertName,
            String service,
            String severity,
            String duration,
            String status
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            String alertName,
            String service,
            String observation,
            String hypothesis,
            String recommendation,
            List<String> evidenceIds
    ) {
    }

    public OpsReportDraft {
        alertSummary = alertSummary == null ? List.of() : List.copyOf(alertSummary);
        findings = findings == null ? List.of() : List.copyOf(findings);
        unresolvedIssues = unresolvedIssues == null ? List.of() : List.copyOf(unresolvedIssues);
    }
}
