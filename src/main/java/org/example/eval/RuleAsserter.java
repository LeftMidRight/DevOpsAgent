package org.example.eval;

import org.example.diagnosis.DiagnosisReport;

public class RuleAsserter {

    public String assertScenario(FaultScenario scenario, DiagnosisReport report) {
        FaultScenario.ScenarioExpect expect = scenario.expect();
        if (report.hypothesisCount() != expect.hypothesisCount()) {
            return "hypothesisCount expected " + expect.hypothesisCount()
                    + " actual " + report.hypothesisCount();
        }
        if (report.supportedCount() < expect.supportedMin()) {
            return "supportedMin expected >=" + expect.supportedMin()
                    + " actual " + report.supportedCount();
        }
        return null;
    }
}
