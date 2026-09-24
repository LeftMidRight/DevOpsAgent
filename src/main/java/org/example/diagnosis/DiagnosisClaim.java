package org.example.diagnosis;

import java.util.List;

public record DiagnosisClaim(
        String statement,
        String alertName,
        String service,
        List<String> evidenceIds,
        ClaimStatus status,
        String reason
) {
    public static DiagnosisClaim draft(String statement, String alertName, String service, List<String> evidenceIds) {
        return new DiagnosisClaim(statement, alertName, service, List.copyOf(evidenceIds), null, null);
    }

    public DiagnosisClaim withStatus(ClaimStatus status, String reason) {
        return new DiagnosisClaim(statement, alertName, service, evidenceIds, status, reason);
    }
}
