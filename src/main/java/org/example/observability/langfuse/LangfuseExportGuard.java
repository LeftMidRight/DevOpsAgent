package org.example.observability.langfuse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Langfuse 打开时必须有 OTLP 地址和 Authorization，避免静默把 Trace 丢到空配置。
 */
@Component
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
public class LangfuseExportGuard {

    static final String ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";
    static final String AUTHORIZATION = "management.opentelemetry.tracing.export.otlp.headers.Authorization";

    private static final Logger logger = LoggerFactory.getLogger(LangfuseExportGuard.class);

    private final Environment environment;

    public LangfuseExportGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String endpoint = environment.getProperty(ENDPOINT, "");
        String authorization = environment.getProperty(AUTHORIZATION, "");
        if (endpoint.isBlank()) {
            throw new IllegalStateException("已启用 Langfuse，但未设置 OTEL_EXPORTER_OTLP_ENDPOINT");
        }
        if (authorization.isBlank()) {
            throw new IllegalStateException("已启用 Langfuse，但未设置 LANGFUSE_OTLP_AUTHORIZATION");
        }
        logger.info("Langfuse tracing enabled, endpoint={}", endpoint);
    }
}
