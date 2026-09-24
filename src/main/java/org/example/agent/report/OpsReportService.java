package org.example.agent.report;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.example.agent.AgentRunContext;
import org.example.agent.RunEvidence;
import org.example.diagnosis.DiagnosisClaim;
import org.example.diagnosis.DiagnosisReport;
import org.example.diagnosis.DiagnosisService;
import org.example.diagnosis.Evidence;
import org.example.diagnosis.EvidenceBundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * OPS 报告链路：结构化草稿解析 →（预算内一次修正）→ 证据引用校验 →
 * 服务端渲染 Markdown。不创建新的评审 Agent；修正只是同模型的格式修复调用。
 */
@Service
public class OpsReportService {

    private static final Logger logger = LoggerFactory.getLogger(OpsReportService.class);

    private static final String FIX_PROMPT = """
            以下内容本应是一个符合约定结构的 JSON 诊断报告草稿，但格式有误。
            请将其修正为**只含一个 JSON 对象**的输出（不要 Markdown、不要代码围栏、不要解释），
            保持原有事实内容不变，不得新增任何未在原文出现的证据编号或结论。
            JSON 结构：{"alertSummary": [...], "findings": [{"alertName","service","observation","hypothesis","recommendation","evidenceIds"}],
            "unresolvedIssues": [...], "overallAssessment": "...", "riskLevel": "..."}。
            待修正内容：
            """;

    private final OpsReportParser parser;
    private final OpsReportRenderer renderer;
    private final DiagnosisService diagnosisService;

    public OpsReportService(OpsReportParser parser, OpsReportRenderer renderer, DiagnosisService diagnosisService) {
        this.parser = parser;
        this.renderer = renderer;
        this.diagnosisService = diagnosisService;
    }

    /**
     * 预算耗尽时交付已有证据与确定性未完成说明，不伪装校验通过。
     */
    public String produceIncomplete(String reason, String rawAnswer, AgentRunContext runContext) {
        return renderer.renderFailure(reason, rawAnswer, runContext.evidences());
    }

    /**
     * 从 Agent 原始输出产出最终报告 Markdown。
     */
    public String produceReport(String rawAnswer, AgentRunContext runContext, ChatModel chatModel) {
        List<RunEvidence> evidences = runContext.evidences();

        OpsReportDraft draft;
        try {
            draft = parser.parse(rawAnswer);
        } catch (OpsReportParser.OpsReportParseException firstFailure) {
            logger.warn("OPS 草稿首次解析失败 - runId: {}, 原因: {}", runContext.runId(), firstFailure.getMessage());
            draft = tryRepairOnce(rawAnswer, runContext, chatModel);
            if (draft == null) {
                return renderer.renderFailure(
                        "结构化报告解析失败（含一次修正尝试）：" + firstFailure.getMessage(),
                        rawAnswer, evidences);
            }
        }

        List<DiagnosisClaim> claims = draft.findings().stream()
                .map(f -> DiagnosisClaim.draft(
                        f.hypothesis() == null || f.hypothesis().isBlank() ? "未给出根因假设" : f.hypothesis(),
                        f.alertName(),
                        f.service(),
                        f.evidenceIds() == null ? List.of() : f.evidenceIds()))
                .toList();
        if (claims.isEmpty()) {
            claims = List.of(DiagnosisClaim.draft("未形成任何根因结论", null, null, List.of()));
        }

        EvidenceBundle bundle = EvidenceBundle.of(
                evidences.stream().map(RunEvidence::toDiagnosisEvidence).map(Evidence.class::cast).toList());
        DiagnosisReport validation = diagnosisService.diagnose(bundle, claims);
        logger.info("OPS 报告校验完成 - runId: {}, 证据: {}, 引用有效: {}, 待验证: {}",
                runContext.runId(), bundle.items().size(), validation.supportedCount(), validation.hypothesisCount());

        return renderer.render(draft, validation, evidences);
    }

    /**
     * 在本轮预算内进行一次格式修正；预算不足或修正后仍无法解析时返回 null。
     */
    private OpsReportDraft tryRepairOnce(String rawAnswer, AgentRunContext runContext, ChatModel chatModel) {
        if (!runContext.canAffordModelCall()) {
            logger.warn("OPS 草稿修正跳过 - runId: {}, 模型预算不足", runContext.runId());
            return null;
        }
        runContext.events().onProgress("报告格式异常，正在修正");
        try {
            runContext.checkModelBudget(true);
            String fixed = chatModel.call(new Prompt(new UserMessage(FIX_PROMPT + truncate(rawAnswer))))
                    .getResult()
                    .getOutput()
                    .getText();
            return parser.parse(fixed);
        } catch (Exception e) {
            logger.warn("OPS 草稿修正失败 - runId: {}, 原因: {}", runContext.runId(), e.getMessage());
            return null;
        }
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > 6000 ? raw.substring(0, 6000) + "...[截断]" : raw;
    }
}
