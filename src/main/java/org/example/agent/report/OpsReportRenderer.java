package org.example.agent.report;

import org.example.agent.RunEvidence;
import org.example.diagnosis.ClaimStatus;
import org.example.diagnosis.DiagnosisClaim;
import org.example.diagnosis.DiagnosisReport;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把校验后的结构化草稿渲染为最终交付的 Markdown 报告。
 * 文案区分"证据引用有效"与"待验证假设"，不宣称根因已被证明。
 */
@Component
public class OpsReportRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public String render(OpsReportDraft draft, DiagnosisReport validation, List<RunEvidence> evidences) {
        Map<String, DiagnosisClaim> claimByKey = validation.claims().stream()
                .collect(Collectors.toMap(
                        c -> claimKey(c.alertName(), c.service(), c.statement()),
                        Function.identity(),
                        (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        sb.append("# 告警分析报告\n\n---\n\n");

        sb.append("## 📋 活跃告警清单\n\n");
        if (draft.alertSummary().isEmpty()) {
            sb.append("未获取到活动告警（或告警查询失败，详见未解决问题）。\n\n");
        } else {
            sb.append("| 告警名称 | 级别 | 目标服务 | 持续时间 | 状态 |\n");
            sb.append("|---------|------|----------|---------|------|\n");
            for (OpsReportDraft.AlertSummaryItem item : draft.alertSummary()) {
                sb.append("| ").append(text(item.alertName()))
                        .append(" | ").append(text(item.severity()))
                        .append(" | ").append(text(item.service()))
                        .append(" | ").append(text(item.duration()))
                        .append(" | ").append(text(item.status()))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        int index = 1;
        for (OpsReportDraft.Finding finding : draft.findings()) {
            sb.append("---\n\n");
            sb.append("## 🔍 告警根因分析").append(index).append(" - ").append(text(finding.alertName())).append("\n\n");
            sb.append("- **受影响服务**: ").append(text(finding.service())).append('\n');
            sb.append("- **观察事实**: ").append(text(finding.observation())).append('\n');
            sb.append("- **待验证原因**: ").append(text(finding.hypothesis())).append('\n');
            sb.append("- **处理建议**: ").append(text(finding.recommendation())).append('\n');

            DiagnosisClaim claim = claimByKey.get(
                    claimKey(finding.alertName(), finding.service(), finding.hypothesis()));
            if (claim != null) {
                sb.append("- **证据校验**: ");
                if (claim.status() == ClaimStatus.SUPPORTED) {
                    sb.append("✅ 证据引用有效");
                } else {
                    sb.append("⚠️ 待验证假设");
                }
                if (claim.reason() != null) {
                    sb.append("（").append(claim.reason()).append("）");
                }
                sb.append('\n');
            }
            if (finding.evidenceIds() != null && !finding.evidenceIds().isEmpty()) {
                sb.append("- **引用证据**: ").append(String.join(", ", finding.evidenceIds())).append('\n');
            }
            sb.append('\n');
            index++;
        }

        if (!draft.unresolvedIssues().isEmpty()) {
            sb.append("---\n\n## ⚠️ 未解决问题\n\n");
            for (String issue : draft.unresolvedIssues()) {
                sb.append("- ").append(issue).append('\n');
            }
            sb.append('\n');
        }

        sb.append("---\n\n## 📊 结论\n\n");
        sb.append("- **整体评估**: ").append(text(draft.overallAssessment())).append('\n');
        sb.append("- **风险评估**: ").append(text(draft.riskLevel())).append('\n');
        sb.append("- **校验统计**: 证据 ").append(evidences.size()).append(" 条，证据引用有效 ")
                .append(validation.supportedCount()).append(" 条，待验证假设 ")
                .append(validation.hypothesisCount()).append(" 条\n\n");

        if (!evidences.isEmpty()) {
            sb.append("### 证据清单\n\n");
            for (RunEvidence evidence : evidences) {
                sb.append("- `").append(evidence.id()).append("` [")
                        .append(evidence.toolName()).append(" / ")
                        .append(evidence.status().name()).append("] ")
                        .append(evidence.alertName() == null ? "未知告警" : evidence.alertName())
                        .append(" / ")
                        .append(evidence.service() == null ? "未知服务" : evidence.service())
                        .append("，采集于 ").append(TIME_FORMAT.format(evidence.calledAt()));
                if (evidence.truncated()) {
                    sb.append("（内容已截断）");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 渲染未通过结构化解析时的确定性兜底报告：如实说明失败原因，
     * 不追加"校验通过"之类的伪装结论。
     */
    public String renderFailure(String reason, String rawAnswer, List<RunEvidence> evidences) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 告警分析报告\n\n---\n\n");
        sb.append("## ⚠️ 报告生成未完成\n\n");
        sb.append("- **原因**: ").append(reason).append('\n');
        sb.append("- **本轮采集证据**: ").append(evidences.size()).append(" 条\n\n");
        if (rawAnswer != null && !rawAnswer.isBlank()) {
            sb.append("### Agent 原始输出（未校验，仅供参考）\n\n");
            sb.append(rawAnswer.length() > 2000 ? rawAnswer.substring(0, 2000) + "\n...[截断]" : rawAnswer)
                    .append("\n\n");
        }
        if (!evidences.isEmpty()) {
            sb.append("### 证据清单\n\n");
            for (RunEvidence evidence : evidences) {
                sb.append("- `").append(evidence.id()).append("` [")
                        .append(evidence.toolName()).append(" / ")
                        .append(evidence.status().name()).append("] ")
                        .append(evidence.alertName() == null ? "未知告警" : evidence.alertName())
                        .append(" / ")
                        .append(evidence.service() == null ? "未知服务" : evidence.service())
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String claimKey(String alertName, String service, String statement) {
        return (alertName == null ? "" : alertName) + "|"
                + (service == null ? "" : service) + "|"
                + (statement == null ? "" : statement);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }
}
