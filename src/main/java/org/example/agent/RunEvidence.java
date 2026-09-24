package org.example.agent;

import org.example.diagnosis.Evidence;
import org.example.diagnosis.EvidenceType;

import java.time.Instant;

/**
 * 本轮任务中一次真实工具调用产生的证据记录。
 * 证据 ID 随工具结果一起提供给模型，最终报告只能引用这里存在的 ID。
 */
public record RunEvidence(
        String id,
        String runId,
        String toolName,
        String source,
        Instant calledAt,
        String arguments,
        EvidenceStatus status,
        String service,
        String alertName,
        String content,
        boolean truncated
) {
    /**
     * 转换为诊断校验使用的证据模型。工具错误或空结果被标记为无效，
     * 校验器不会让它们支撑任何结论。
     */
    public Evidence toDiagnosisEvidence() {
        return new Evidence(
                id,
                toEvidenceType(toolName),
                source,
                service,
                alertName,
                content,
                status == EvidenceStatus.SUCCESS);
    }

    private static EvidenceType toEvidenceType(String toolName) {
        if (toolName == null) {
            return EvidenceType.DOCUMENT;
        }
        String name = toolName.toLowerCase();
        if (name.contains("alert") || name.contains("prometheus") || name.contains("metric")) {
            return EvidenceType.ALERT;
        }
        if (name.contains("log") || name.contains("cls")) {
            return EvidenceType.LOG;
        }
        return EvidenceType.DOCUMENT;
    }
}
