package org.example.eval;

import org.example.diagnosis.DiagnosisReport;
import org.example.diagnosis.DiagnosisService;
import org.example.diagnosis.EvidenceBundle;
import org.example.diagnosis.EvidenceParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock 回放 40 个故障场景：不调 DashScope，只跑证据校验 + 规则断言 + 空 LLM 评审。
 */
public class ScenarioRunner {

    private final DiagnosisService diagnosisService;
    private final RuleAsserter ruleAsserter;
    private final LlmJudge llmJudge;

    public ScenarioRunner() {
        this(new DiagnosisService(new EvidenceParser()), new RuleAsserter(), new LlmJudge.NoopLlmJudge());
    }

    public ScenarioRunner(DiagnosisService diagnosisService, RuleAsserter ruleAsserter, LlmJudge llmJudge) {
        this.diagnosisService = diagnosisService;
        this.ruleAsserter = ruleAsserter;
        this.llmJudge = llmJudge;
    }

    public EvalResult run() {
        return run(ScenarioCatalog.load());
    }

    public EvalResult run(List<FaultScenario> scenarios) {
        List<ScenarioOutcome> outcomes = new ArrayList<>();
        for (FaultScenario scenario : scenarios) {
            AgentTrace.Builder trace = AgentTrace.builder(scenario.id());
            trace.tool("queryPrometheusAlerts", "mock replay " + scenario.category());
            trace.tool("queryInternalDocs", "mock replay");
            if (scenario.category() != ScenarioCategory.TOOL_ERROR) {
                trace.tool("queryLogs", "mock replay");
            } else {
                trace.tool("queryLogs", "mock error");
            }
            DiagnosisReport report = diagnosisService.diagnose(
                    EvidenceBundle.of(scenario.evidences()),
                    scenario.claims(),
                    scenario.supplemental()
            );
            String ruleError = ruleAsserter.assertScenario(scenario, report);
            LlmJudge.JudgeResult judge = llmJudge.review(scenario, report);
            boolean pass = ruleError == null && judge.pass() && scenario.expect().pass();
            AgentTrace built = trace.build();
            outcomes.add(new ScenarioOutcome(scenario, report, built, pass, ruleError, judge.comment()));
        }
        return EvalResult.from(outcomes);
    }

    public record ScenarioOutcome(
            FaultScenario scenario,
            DiagnosisReport report,
            AgentTrace trace,
            boolean pass,
            String ruleError,
            String judgeComment
    ) {}

    public record EvalResult(
            int total,
            int passed,
            double passRate,
            long p95Ms,
            List<ScenarioOutcome> outcomes
    ) {
        static EvalResult from(List<ScenarioOutcome> outcomes) {
            int passed = (int) outcomes.stream().filter(ScenarioOutcome::pass).count();
            List<Long> latencies = outcomes.stream()
                    .map(o -> o.trace().elapsedMs())
                    .sorted()
                    .toList();
            long p95 = percentile(latencies, 0.95);
            double rate = outcomes.isEmpty() ? 0 : (double) passed / outcomes.size();
            return new EvalResult(outcomes.size(), passed, rate, p95, outcomes);
        }

        public String summary() {
            return String.format(
                    "scenarios=%d passed=%d passRate=%.2f%% p95=%dms",
                    total, passed, passRate * 100, p95Ms);
        }

        private static long percentile(List<Long> sorted, double p) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int idx = (int) Math.ceil(p * sorted.size()) - 1;
            return sorted.get(Math.min(Math.max(idx, 0), sorted.size() - 1));
        }
    }
}
