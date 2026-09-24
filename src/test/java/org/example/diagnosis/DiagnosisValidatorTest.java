package org.example.diagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisValidatorTest {

    private final DiagnosisValidator validator = new DiagnosisValidator(1);

    @Test
    void citedEvidenceMarksClaimSupported() {
        Evidence alert = Evidence.alert("e1", "prometheus", "api", "HighCPUUsage", "CPU 82%");
        DiagnosisClaim claim = DiagnosisClaim.draft(
                "api CPU 过高", "HighCPUUsage", "api", List.of("e1"));

        DiagnosisReport report = validator.validate(EvidenceBundle.of(alert), List.of(claim), 0);

        assertEquals(ClaimStatus.SUPPORTED, report.claims().get(0).status());
        assertEquals(0, report.hypothesisCount());
    }

    @Test
    void missingCitationMarksHypothesis() {
        Evidence alert = Evidence.alert("e1", "prometheus", "api", "HighCPUUsage", "CPU 82%");
        DiagnosisClaim claim = DiagnosisClaim.draft("原因不明", "HighCPUUsage", "api", List.of());

        DiagnosisReport report = validator.validate(EvidenceBundle.of(alert), List.of(claim), 0);

        assertEquals(ClaimStatus.HYPOTHESIS, report.claims().get(0).status());
        assertTrue(report.claims().get(0).reason().contains("证据不足"));
    }

    @Test
    void crossServiceCitationMarksHypothesis() {
        Evidence other = Evidence.alert("e2", "prometheus", "billing", "HighMemoryUsage", "mem 90%");
        DiagnosisClaim claim = DiagnosisClaim.draft(
                "api CPU 过高", "HighCPUUsage", "api", List.of("e2"));

        DiagnosisReport report = validator.validate(EvidenceBundle.of(other), List.of(claim), 0);

        assertEquals(ClaimStatus.HYPOTHESIS, report.claims().get(0).status());
        assertTrue(report.claims().get(0).reason().contains("跨服务"));
    }

    @Test
    void emptyBundleMarksHypothesis() {
        DiagnosisClaim claim = DiagnosisClaim.draft("推测 OOM", "HighMemoryUsage", "api", List.of("missing"));

        DiagnosisReport report = validator.validate(EvidenceBundle.empty(), List.of(claim), 0);

        assertEquals(ClaimStatus.HYPOTHESIS, report.claims().get(0).status());
    }

    @Test
    void unknownOwnershipDoesNotCountAsSupported() {
        Evidence unknown = new Evidence("ev-1", EvidenceType.LOG, "test", null, null, "unknown", true);
        DiagnosisClaim claim = DiagnosisClaim.draft("unknown root cause", null, null, List.of("ev-1"));

        DiagnosisReport report = validator.validate(EvidenceBundle.of(unknown), List.of(claim), 0);

        assertEquals(ClaimStatus.HYPOTHESIS, report.claims().get(0).status());
        assertTrue(report.claims().get(0).reason().contains("未知归属"));
    }
}
