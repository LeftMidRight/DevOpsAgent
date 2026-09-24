package org.example.diagnosis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 诊断闭环：校验结论与证据；证据不足且仍有补查额度时，并入补充证据后再校验一次。
 */
@Service
public class DiagnosisService {

    public static final int DEFAULT_MAX_LOOKUPS = 1;

    private final DiagnosisValidator validator;
    private final EvidenceParser evidenceParser;

    @Autowired
    public DiagnosisService(EvidenceParser evidenceParser) {
        this(evidenceParser, new DiagnosisValidator(DEFAULT_MAX_LOOKUPS));
    }

    public DiagnosisService(EvidenceParser evidenceParser, DiagnosisValidator validator) {
        this.evidenceParser = evidenceParser;
        this.validator = validator;
    }

    public DiagnosisReport diagnose(EvidenceBundle bundle, List<DiagnosisClaim> drafts) {
        return diagnose(bundle, drafts, List.of());
    }

    public DiagnosisReport diagnose(
            EvidenceBundle bundle,
            List<DiagnosisClaim> drafts,
            List<Evidence> supplemental
    ) {
        List<DiagnosisClaim> claims = drafts == null || drafts.isEmpty()
                ? defaultClaims(bundle)
                : drafts;
        DiagnosisReport first = validator.validate(bundle, claims, 0);
        if (first.hypothesisCount() == 0 || supplemental == null || supplemental.isEmpty()) {
            return first;
        }
        if (first.lookupsUsed() >= validator.maxLookups()) {
            return first;
        }
        EvidenceBundle enriched = bundle.plus(supplemental);
        return validator.validate(enriched, claims, 1);
    }

    public DiagnosisReport fromExecutorFeedback(String plannerReport, String executorFeedback) {
        EvidenceBundle bundle = evidenceParser.parseExecutorFeedback(executorFeedback);
        Evidence first = bundle.isEmpty() ? null : bundle.items().get(0);
        DiagnosisClaim claim = DiagnosisClaim.draft(
                plannerReport == null || plannerReport.isBlank() ? "未能从 Planner 得到结论" : "Planner 报告中的根因结论",
                first == null ? null : first.alertName(),
                first == null ? null : first.service(),
                bundle.items().stream().map(Evidence::id).toList()
        );
        return diagnose(bundle, List.of(claim));
    }

    private static List<DiagnosisClaim> defaultClaims(EvidenceBundle bundle) {
        if (bundle.isEmpty()) {
            return List.of(DiagnosisClaim.draft("证据为空，无法形成结论", null, null, List.of()));
        }
        Evidence first = bundle.items().get(0);
        return List.of(DiagnosisClaim.draft(
                "基于已收集证据给出诊断",
                first.alertName(),
                first.service(),
                bundle.items().stream().map(Evidence::id).toList()
        ));
    }
}
