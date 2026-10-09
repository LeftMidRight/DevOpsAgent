package org.example.config;

import org.example.observability.langfuse.LangfuseProperties;
import org.example.observability.loki.LokiProperties;
import org.example.observability.prometheus.PrometheusProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({PrometheusProperties.class, LokiProperties.class, LangfuseProperties.class})
public class ObservabilityConfig {
}
