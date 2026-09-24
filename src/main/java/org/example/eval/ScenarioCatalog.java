package org.example.eval;

import org.example.diagnosis.DiagnosisClaim;
import org.example.diagnosis.Evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置 40 个 Mock 故障场景：正常诊断、证据缺失、跨服务误关联、工具异常。
 */
public final class ScenarioCatalog {

    private ScenarioCatalog() {}

    public static List<FaultScenario> load() {
        List<FaultScenario> scenarios = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            scenarios.add(normal(i));
        }
        for (int i = 1; i <= 10; i++) {
            scenarios.add(missingEvidence(i));
        }
        for (int i = 1; i <= 10; i++) {
            scenarios.add(crossService(i));
        }
        for (int i = 1; i <= 8; i++) {
            scenarios.add(toolError(i));
        }
        return List.copyOf(scenarios);
    }

    private static FaultScenario normal(int i) {
        String alert = i % 2 == 0 ? "HighMemoryUsage" : "HighCPUUsage";
        String service = "api-" + i;
        Evidence alertEv = Evidence.alert("a-" + i, "prometheus", service, alert, alert + " firing");
        Evidence logEv = Evidence.log("l-" + i, "cls", service, alert, "matched error logs");
        Evidence docEv = Evidence.document("d-" + i, "kb", service, alert, "runbook step");
        DiagnosisClaim claim = DiagnosisClaim.draft(
                service + " 命中 " + alert, alert, service, List.of("a-" + i, "l-" + i, "d-" + i));
        return new FaultScenario(
                "normal-" + pad(i),
                ScenarioCategory.NORMAL,
                "正常诊断 " + alert,
                List.of(alertEv, logEv, docEv),
                List.of(claim),
                List.of(),
                new FaultScenario.ScenarioExpect(0, 1, true)
        );
    }

    private static FaultScenario missingEvidence(int i) {
        String alert = "SlowResponse";
        String service = "gateway-" + i;
        Evidence supplemental = Evidence.log("s-" + i, "cls", service, alert, "补查到慢查询日志");
        DiagnosisClaim claim = DiagnosisClaim.draft(
                service + " 响应变慢", alert, service, List.of("s-" + i));
        return new FaultScenario(
                "missing-" + pad(i),
                ScenarioCategory.MISSING_EVIDENCE,
                "证据缺失后有限补查",
                List.of(),
                List.of(claim),
                List.of(supplemental),
                new FaultScenario.ScenarioExpect(0, 1, true)
        );
    }

    private static FaultScenario crossService(int i) {
        String service = "checkout-" + i;
        Evidence other = Evidence.alert("x-" + i, "prometheus", "unrelated-db", "HighDiskUsage", "disk 91%");
        DiagnosisClaim claim = DiagnosisClaim.draft(
                service + " 不可用", "ServiceUnavailable", service, List.of("x-" + i));
        return new FaultScenario(
                "cross-" + pad(i),
                ScenarioCategory.CROSS_SERVICE,
                "跨服务误关联",
                List.of(other),
                List.of(claim),
                List.of(),
                new FaultScenario.ScenarioExpect(1, 0, true)
        );
    }

    private static FaultScenario toolError(int i) {
        DiagnosisClaim claim = DiagnosisClaim.draft(
                "工具失败后的猜测", "HighCPUUsage", "worker-" + i, List.of("missing-tool-" + i));
        return new FaultScenario(
                "toolerr-" + pad(i),
                ScenarioCategory.TOOL_ERROR,
                "工具异常无证据",
                List.of(),
                List.of(claim),
                List.of(),
                new FaultScenario.ScenarioExpect(1, 0, true)
        );
    }

    private static String pad(int i) {
        return i < 10 ? "0" + i : String.valueOf(i);
    }
}
