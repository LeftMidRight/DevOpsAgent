package org.example.diagnosis;

public record Evidence(
        String id,
        EvidenceType type,
        String source,
        String service,
        String alertName,
        String content,
        boolean valid
) {
    /**
     * 兼容构造：默认有效证据。工具错误或空结果应使用完整构造器标记为无效。
     */
    public Evidence(String id, EvidenceType type, String source, String service, String alertName, String content) {
        this(id, type, source, service, alertName, content, true);
    }

    public static Evidence alert(String id, String source, String service, String alertName, String content) {
        return new Evidence(id, EvidenceType.ALERT, source, service, alertName, content);
    }

    public static Evidence log(String id, String source, String service, String alertName, String content) {
        return new Evidence(id, EvidenceType.LOG, source, service, alertName, content);
    }

    public static Evidence document(String id, String source, String service, String alertName, String content) {
        return new Evidence(id, EvidenceType.DOCUMENT, source, service, alertName, content);
    }

    /**
     * 结论归属匹配：结论必须给出服务或告警中的至少一项具体归属；
     * 未知对未知、或结论指定了归属而证据侧未知，都不能视为匹配成功。
     */
    public boolean matchesClaim(String claimAlertName, String claimService) {
        boolean claimHasAlert = claimAlertName != null && !claimAlertName.isBlank();
        boolean claimHasService = claimService != null && !claimService.isBlank();
        if (!claimHasAlert && !claimHasService) {
            return false;
        }
        if (claimHasAlert) {
            if (alertName == null || alertName.isBlank() || !claimAlertName.equals(alertName)) {
                return false;
            }
        }
        if (claimHasService) {
            if (service == null || service.isBlank() || !claimService.equals(service)) {
                return false;
            }
        }
        return true;
    }
}
