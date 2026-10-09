package org.example.observability;

/**
 * Prometheus 或 Loki 查询失败。工具层把它转成 query_error，不把它伪装成空结果。
 */
public class ObservabilityAccessException extends RuntimeException {

    private final String errorType;

    public ObservabilityAccessException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public ObservabilityAccessException(String errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public String errorType() {
        return errorType;
    }
}
