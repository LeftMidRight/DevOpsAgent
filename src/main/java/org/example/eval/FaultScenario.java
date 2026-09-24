package org.example.eval;

import org.example.diagnosis.DiagnosisClaim;
import org.example.diagnosis.Evidence;

import java.util.List;

public record FaultScenario(
        String id,
        ScenarioCategory category,
        String name,
        List<Evidence> evidences,
        List<DiagnosisClaim> claims,
        List<Evidence> supplemental,
        ScenarioExpect expect
) {
    public record ScenarioExpect(int hypothesisCount, int supportedMin, boolean pass) {}
}
