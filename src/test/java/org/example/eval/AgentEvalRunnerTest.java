package org.example.eval;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvalRunnerTest {

    @Test
    void runsFortyMockScenarios() {
        ScenarioRunner.EvalResult result = new ScenarioRunner().run();

        System.out.println(result.summary());
        result.outcomes().stream()
                .filter(o -> !o.pass())
                .forEach(o -> System.out.println("FAIL " + o.scenario().id() + " " + o.ruleError()));

        assertEquals(40, result.total());
        Map<ScenarioCategory, Long> counts = new EnumMap<>(ScenarioCategory.class);
        for (ScenarioCategory category : ScenarioCategory.values()) {
            long n = result.outcomes().stream()
                    .filter(o -> o.scenario().category() == category)
                    .count();
            counts.put(category, n);
        }
        assertEquals(12, counts.get(ScenarioCategory.NORMAL));
        assertEquals(10, counts.get(ScenarioCategory.MISSING_EVIDENCE));
        assertEquals(10, counts.get(ScenarioCategory.CROSS_SERVICE));
        assertEquals(8, counts.get(ScenarioCategory.TOOL_ERROR));
        assertEquals(result.total(), result.passed());
        assertEquals(1.0, result.passRate());
        assertTrue(result.p95Ms() >= 1);
    }
}
