package org.example.observability.prometheus;

import java.util.List;

/**
 * Prometheus 诊断工具的稳定返回结构。空字段不输出，避免把缺失信号伪装成 0。
 */
public final class PrometheusReports {

    private PrometheusReports() {
    }

    public record MetricSignal(String status, Double current, Double average, Double max, String trend) {

        public static MetricSignal present(double current, double average, double max, String trend) {
            return new MetricSignal("ok", current, average, max, trend);
        }

        public static MetricSignal missing() {
            return new MetricSignal("no_data", null, null, null, null);
        }
    }

    public record ServiceInstance(String instance, double errorRate, boolean abnormal) {
    }

    public record ResourceInstance(String instance, Double processCpu, Double processMemoryBytes, boolean abnormal) {
    }

    public record JvmInstance(String instance, double oldGenUsage, boolean abnormal) {
    }

    public record AlertView(
            String alertName,
            String service,
            String instance,
            String severity,
            String state,
            String activeSince,
            String description
    ) {
    }

    public record ActiveAlerts(String status, List<AlertView> alerts, boolean truncated) {
    }

    public record TargetView(
            String instance,
            String targetHealth,
            String lastError,
            String lastScrape,
            String scrapeUrl
    ) {
    }

    public record TargetCheck(
            String status,
            String service,
            String targetHealth,
            String lastError,
            String lastScrape,
            String scrapeUrl,
            String message,
            List<TargetView> targets
    ) {
    }

    public record ServiceHealth(
            String status,
            String service,
            String range,
            String message,
            String targetHealth,
            String lastError,
            String lastScrape,
            String scrapeUrl,
            MetricSignal requestRate,
            MetricSignal errorRate,
            MetricSignal latencyP95Ms,
            MetricSignal latencyP99Ms,
            List<ServiceInstance> instances
    ) {
    }

    public record ResourceUsage(
            String status,
            String service,
            String range,
            String message,
            String targetHealth,
            String lastError,
            String lastScrape,
            String scrapeUrl,
            MetricSignal processCpu,
            MetricSignal processMemoryBytes,
            List<ResourceInstance> instances
    ) {
    }

    public record JvmHealth(
            String status,
            String service,
            String range,
            String message,
            String targetHealth,
            String lastError,
            String lastScrape,
            String scrapeUrl,
            MetricSignal heapUsage,
            MetricSignal oldGenUsage,
            MetricSignal gcRate,
            MetricSignal gcPauseP99Ms,
            MetricSignal threads,
            List<JvmInstance> instances
    ) {
    }
}
