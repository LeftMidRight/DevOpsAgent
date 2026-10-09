package org.example.observability.prometheus;

import org.example.observability.ObservabilityNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricQueryRegistryTest {

    @Test
    void templatesUseFixedMetricsAndValidatedSelector() {
        String selector = ObservabilityNames.selector("payment-service", "10.0.0.12:8080");
        String requestRate = MetricQueryRegistry.requestRate(selector, "5m");
        assertEquals(
                "sum(rate(http_server_requests_seconds_count{service=\"payment-service\",instance=\"10.0.0.12:8080\"}[5m]))",
                requestRate);
        String errorRate = MetricQueryRegistry.errorRate(selector, "5m");
        assertFalse(errorRate.contains("metric="));
        assertTrue(errorRate.contains("or vector(0)"));
        assertEquals(
                "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket"
                        + selector + "[5m])))",
                MetricQueryRegistry.latencyQuantile("0.95", selector, "5m"));
        assertEquals("avg(process_cpu_usage" + selector + ")", MetricQueryRegistry.processCpu(selector));
        assertEquals("sum(jvm_threads_live_threads" + selector + ")", MetricQueryRegistry.threadCount(selector));
    }
}
