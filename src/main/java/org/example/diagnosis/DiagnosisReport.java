package org.example.diagnosis;

import java.util.List;
import java.util.stream.Collectors;

public record DiagnosisReport(
        EvidenceBundle bundle,
        List<DiagnosisClaim> claims,
        int lookupsUsed
) {
    public long hypothesisCount() {
        return claims.stream().filter(c -> c.status() == ClaimStatus.HYPOTHESIS).count();
    }

    public long supportedCount() {
        return claims.stream().filter(c -> c.status() == ClaimStatus.SUPPORTED).count();
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("## 诊断校验\n\n");
        sb.append("- 证据条数: ").append(bundle.items().size()).append('\n');
        sb.append("- 已支持结论: ").append(supportedCount()).append('\n');
        sb.append("- 待验证假设: ").append(hypothesisCount()).append('\n');
        sb.append("- 补查次数: ").append(lookupsUsed).append("\n\n");
        for (DiagnosisClaim claim : claims) {
            sb.append("- **").append(claim.status()).append("**: ").append(claim.statement());
            if (claim.reason() != null) {
                sb.append("（").append(claim.reason()).append("）");
            }
            if (!claim.evidenceIds().isEmpty()) {
                sb.append(" 引用: ").append(String.join(", ", claim.evidenceIds()));
            }
            sb.append('\n');
        }
        if (!bundle.items().isEmpty()) {
            sb.append("\n### 证据清单\n");
            sb.append(bundle.items().stream()
                    .map(e -> "- `" + e.id() + "` [" + e.type() + "] " + e.alertName() + " / " + e.service()
                            + " ← " + e.source())
                    .collect(Collectors.joining("\n")));
            sb.append('\n');
        }
        return sb.toString();
    }
}
