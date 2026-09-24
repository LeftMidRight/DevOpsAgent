package org.example.diagnosis;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则校验：结论必须引用存在的证据；引用工具失败/空结果等无效证据不成立；
 * 告警名/服务对不上视为跨服务误关联；证据不足时标记为待验证假设。
 */
public class DiagnosisValidator {

    private final int maxLookups;

    public DiagnosisValidator(int maxLookups) {
        this.maxLookups = Math.max(0, maxLookups);
    }

    public int maxLookups() {
        return maxLookups;
    }

    public DiagnosisReport validate(EvidenceBundle bundle, List<DiagnosisClaim> drafts, int lookupsUsed) {
        List<DiagnosisClaim> result = new ArrayList<>();
        for (DiagnosisClaim draft : drafts) {
            result.add(check(bundle, draft));
        }
        return new DiagnosisReport(bundle, result, lookupsUsed);
    }

    private DiagnosisClaim check(EvidenceBundle bundle, DiagnosisClaim draft) {
        if (draft.evidenceIds() == null || draft.evidenceIds().isEmpty()) {
            return draft.withStatus(ClaimStatus.HYPOTHESIS, "证据不足：结论未引用任何证据");
        }
        for (String id : draft.evidenceIds()) {
            var found = bundle.find(id);
            if (found.isEmpty()) {
                return draft.withStatus(ClaimStatus.HYPOTHESIS, "证据不足：引用不存在的证据 " + id);
            }
            Evidence evidence = found.get();
            if (!evidence.valid()) {
                return draft.withStatus(ClaimStatus.HYPOTHESIS,
                        "引用无效证据：" + id + " 来自工具失败或空结果，不能支撑结论");
            }
            if (!evidence.matchesClaim(draft.alertName(), draft.service())) {
                boolean claimHasOwnership = (draft.alertName() != null && !draft.alertName().isBlank())
                        || (draft.service() != null && !draft.service().isBlank());
                if (!claimHasOwnership) {
                    return draft.withStatus(ClaimStatus.HYPOTHESIS,
                            "未知归属：结论与证据均未标明服务或告警，不能视为匹配成功");
                }
                return draft.withStatus(ClaimStatus.HYPOTHESIS,
                        "跨服务误关联：证据 " + id + " 不属于 " + draft.service() + "/" + draft.alertName());
            }
        }
        return draft.withStatus(ClaimStatus.SUPPORTED, "结论已关联证据 " + String.join(",", draft.evidenceIds()));
    }
}
