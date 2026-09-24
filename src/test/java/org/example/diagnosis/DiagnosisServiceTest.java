package org.example.diagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagnosisServiceTest {

    private final DiagnosisService service = new DiagnosisService(new EvidenceParser());

    @Test
    void supplementalLookupCanSupportPreviouslyMissingEvidence() {
        DiagnosisClaim claim = DiagnosisClaim.draft("慢查询", "SlowResponse", "gw", List.of("s1"));
        Evidence extra = Evidence.log("s1", "cls", "gw", "SlowResponse", "slow query");

        DiagnosisReport report = service.diagnose(EvidenceBundle.empty(), List.of(claim), List.of(extra));

        assertEquals(ClaimStatus.SUPPORTED, report.claims().get(0).status());
        assertEquals(1, report.lookupsUsed());
        assertEquals(1, report.bundle().items().size());
    }

    @Test
    void parsesExecutorJsonEvidence() {
        String json = "{\"status\":\"SUCCESS\",\"evidence\":[{\"type\":\"ALERT\",\"source\":\"prometheus\",\"service\":\"api\",\"alertName\":\"HighCPUUsage\",\"content\":\"cpu 90\"}]}";
        DiagnosisReport report = service.fromExecutorFeedback("# 报告", json);
        assertEquals(1, report.bundle().items().size());
        assertEquals(ClaimStatus.SUPPORTED, report.claims().get(0).status());
    }
}
