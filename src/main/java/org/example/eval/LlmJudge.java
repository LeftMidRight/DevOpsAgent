package org.example.eval;

import org.example.diagnosis.DiagnosisReport;

/**
 * LLM 评审接口。默认实现不调用模型，便于本地把 40 场景跑通。
 */
public interface LlmJudge {

    JudgeResult review(FaultScenario scenario, DiagnosisReport report);

    record JudgeResult(boolean pass, String comment) {}

    final class NoopLlmJudge implements LlmJudge {
        @Override
        public JudgeResult review(FaultScenario scenario, DiagnosisReport report) {
            return new JudgeResult(true, "noop");
        }
    }
}
