package org.example.agent;

/**
 * 工具返回内容的有效性状态。失败与空结果不伪装成有效证据。
 */
public enum EvidenceStatus {
    SUCCESS,
    EMPTY,
    ERROR
}
