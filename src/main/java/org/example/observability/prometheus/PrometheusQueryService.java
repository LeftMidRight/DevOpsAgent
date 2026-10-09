package org.example.observability.prometheus;

import org.example.observability.ObservabilityNames;
import org.example.observability.SeriesMath;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次诊断意图拆成多条固定 PromQL，并压成摘要。
 */
@Service
public class PrometheusQueryService {

    static final int MAX_ALERTS = 50;
    static final int MAX_INSTANCES = 5;
    static final double ERROR_RATE_ABNORMAL = 0.05;
    static final double CPU_ABNORMAL = 0.8;
    static final double OLD_GEN_ABNORMAL = 0.85;

    private final PrometheusClient client;

    public PrometheusQueryService(PrometheusClient client) {
        this.client = client;
    }

    public PrometheusReports.ActiveAlerts queryActiveAlerts(String service) {
        JsonNode alerts = client.alerts().path("alerts");
        List<PrometheusReports.AlertView> views = new ArrayList<>();
        if (alerts.isArray()) {
            for (JsonNode alert : alerts) {
                PrometheusReports.AlertView view = toAlert(alert);
                if (view == null) {
                    continue;
                }
                if (service != null && !service.equals(view.service())) {
                    continue;
                }
                views.add(view);
            }
        }
        boolean truncated = views.size() > MAX_ALERTS;
        if (truncated) {
            views = new ArrayList<>(views.subList(0, MAX_ALERTS));
        }
        return new PrometheusReports.ActiveAlerts("success", List.copyOf(views), truncated);
    }

    public PrometheusReports.ServiceHealth queryServiceHealth(String service, String instance, String rangeLabel,
                                                               Duration lookback) {
        String selector = ObservabilityNames.selector(service, instance);
        String window = ObservabilityNames.rateWindow(lookback);
        PrometheusReports.MetricSignal requestRate = rangeSignal(
                MetricQueryRegistry.requestRate(selector, window), lookback, 1);
        PrometheusReports.MetricSignal errorRate = rangeSignal(
                MetricQueryRegistry.errorRate(selector, window), lookback, 1);
        PrometheusReports.MetricSignal p95 = rangeSignal(
                MetricQueryRegistry.latencyQuantile("0.95", selector, window), lookback, 1000);
        PrometheusReports.MetricSignal p99 = rangeSignal(
                MetricQueryRegistry.latencyQuantile("0.99", selector, window), lookback, 1000);
        if (allMissing(requestRate, errorRate, p95, p99)) {
            return serviceUnavailable(service, instance, rangeLabel);
        }
        List<PrometheusReports.ServiceInstance> instances = null;
        if (errorRate != null) {
            instances = errorInstances(selector, window);
        }
        return new PrometheusReports.ServiceHealth(
                "success", service, rangeLabel, null, null, null, null, null,
                orMissing(requestRate), orMissing(errorRate), orMissing(p95), orMissing(p99), instances);
    }

    public PrometheusReports.ResourceUsage queryResourceUsage(String service, String instance, String rangeLabel,
                                                              Duration lookback) {
        String selector = ObservabilityNames.selector(service, instance);
        PrometheusReports.MetricSignal cpu = rangeSignal(MetricQueryRegistry.processCpu(selector), lookback, 1);
        PrometheusReports.MetricSignal memory = rangeSignal(MetricQueryRegistry.processMemory(selector), lookback, 1);
        if (allMissing(cpu, memory)) {
            return resourceUnavailable(service, instance, rangeLabel);
        }
        List<PrometheusReports.ResourceInstance> instances = resourceInstances(selector);
        return new PrometheusReports.ResourceUsage(
                "success", service, rangeLabel, null, null, null, null, null,
                orMissing(cpu), orMissing(memory), instances);
    }

    public PrometheusReports.JvmHealth queryJvmHealth(String service, String instance, String rangeLabel,
                                                      Duration lookback) {
        String selector = ObservabilityNames.selector(service, instance);
        String window = ObservabilityNames.rateWindow(lookback);
        PrometheusReports.MetricSignal heap = rangeSignal(MetricQueryRegistry.jvmHeapUsage(selector), lookback, 1);
        PrometheusReports.MetricSignal oldGen = rangeSignal(MetricQueryRegistry.jvmOldGenUsage(selector), lookback, 1);
        PrometheusReports.MetricSignal gcRate = rangeSignal(MetricQueryRegistry.gcRate(selector, window), lookback, 1);
        PrometheusReports.MetricSignal gcPause = rangeSignal(
                MetricQueryRegistry.gcPauseP99(selector, window), lookback, 1000);
        PrometheusReports.MetricSignal threads = rangeSignal(MetricQueryRegistry.threadCount(selector), lookback, 1);
        if (allMissing(heap, oldGen, gcRate, gcPause, threads)) {
            return jvmUnavailable(service, instance, rangeLabel);
        }
        List<PrometheusReports.JvmInstance> instances = oldGen == null ? null : jvmInstances(selector);
        return new PrometheusReports.JvmHealth(
                "success", service, rangeLabel, null, null, null, null, null,
                orMissing(heap), orMissing(oldGen), orMissing(gcRate), orMissing(gcPause), orMissing(threads),
                instances);
    }

    public PrometheusReports.TargetCheck checkMonitoringTarget(String service, String instance) {
        JsonNode active = client.targets().path("activeTargets");
        List<PrometheusReports.TargetView> matched = new ArrayList<>();
        if (active.isArray()) {
            for (JsonNode target : active) {
                JsonNode labels = target.path("labels");
                if (!service.equals(labels.path("service").asText(""))) {
                    continue;
                }
                String labelInstance = blankToNull(labels.path("instance").asText(""));
                if (instance != null && !instance.equals(labelInstance)) {
                    continue;
                }
                matched.add(new PrometheusReports.TargetView(
                        labelInstance,
                        blankToNull(target.path("health").asText("")),
                        blankToNull(target.path("lastError").asText("")),
                        blankToNull(target.path("lastScrape").asText("")),
                        blankToNull(target.path("scrapeUrl").asText(""))));
            }
        }
        if (matched.isEmpty()) {
            return new PrometheusReports.TargetCheck(
                    "no_data", service, "absent", null, null, null,
                    "没有找到带有该 service 标签的 Prometheus 采集目标", List.of());
        }
        boolean anyDown = matched.stream().anyMatch(target -> "down".equals(target.targetHealth()));
        boolean anyUp = matched.stream().anyMatch(target -> "up".equals(target.targetHealth()));
        String health = anyDown && anyUp ? "mixed" : anyDown ? "down" : "up";
        PrometheusReports.TargetView sample = matched.stream()
                .filter(target -> "down".equals(target.targetHealth()))
                .findFirst()
                .orElse(matched.get(0));
        String message = switch (health) {
            case "down" -> "Prometheus 采集目标不可用";
            case "mixed" -> "部分 Prometheus 采集目标不可用";
            default -> null;
        };
        return new PrometheusReports.TargetCheck(
                "success", service, health, sample.lastError(), sample.lastScrape(), sample.scrapeUrl(),
                message, List.copyOf(matched));
    }

    private PrometheusReports.ServiceHealth serviceUnavailable(String service, String instance, String rangeLabel) {
        PrometheusReports.TargetCheck check = checkMonitoringTarget(service, instance);
        if ("down".equals(check.targetHealth())) {
            return new PrometheusReports.ServiceHealth(
                    "target_down", service, rangeLabel, check.message(), check.targetHealth(),
                    check.lastError(), check.lastScrape(), check.scrapeUrl(),
                    null, null, null, null, null);
        }
        return new PrometheusReports.ServiceHealth(
                "no_data", service, rangeLabel, noDataMessage(check), check.targetHealth(),
                null, null, null, null, null, null, null, null);
    }

    private PrometheusReports.ResourceUsage resourceUnavailable(String service, String instance, String rangeLabel) {
        PrometheusReports.TargetCheck check = checkMonitoringTarget(service, instance);
        if ("down".equals(check.targetHealth())) {
            return new PrometheusReports.ResourceUsage(
                    "target_down", service, rangeLabel, check.message(), check.targetHealth(),
                    check.lastError(), check.lastScrape(), check.scrapeUrl(), null, null, null);
        }
        return new PrometheusReports.ResourceUsage(
                "no_data", service, rangeLabel, noDataMessage(check), check.targetHealth(),
                null, null, null, null, null, null);
    }

    private PrometheusReports.JvmHealth jvmUnavailable(String service, String instance, String rangeLabel) {
        PrometheusReports.TargetCheck check = checkMonitoringTarget(service, instance);
        if ("down".equals(check.targetHealth())) {
            return new PrometheusReports.JvmHealth(
                    "target_down", service, rangeLabel, check.message(), check.targetHealth(),
                    check.lastError(), check.lastScrape(), check.scrapeUrl(),
                    null, null, null, null, null, null);
        }
        return new PrometheusReports.JvmHealth(
                "no_data", service, rangeLabel, noDataMessage(check), check.targetHealth(),
                null, null, null, null, null, null, null, null, null);
    }

    private static String noDataMessage(PrometheusReports.TargetCheck check) {
        if ("absent".equals(check.targetHealth())) {
            return check.message();
        }
        if ("mixed".equals(check.targetHealth())) {
            return "部分采集目标不可用，且查询窗口内没有返回指标";
        }
        return "采集目标正常，但查询窗口内没有返回指标";
    }

    private PrometheusReports.MetricSignal rangeSignal(String promql, Duration lookback, double scale) {
        Instant end = Instant.now();
        JsonNode data = client.queryRange(
                promql, end.minus(lookback), end, ObservabilityNames.step(lookback));
        List<Double> values = PrometheusSeries.values(data);
        if (scale != 1d) {
            values = values.stream().map(value -> value * scale).toList();
        }
        SeriesMath.Summary summary = SeriesMath.summarize(values);
        if (summary == null) {
            return null;
        }
        return PrometheusReports.MetricSignal.present(summary.current(), summary.average(), summary.max(), summary.trend());
    }

    private List<PrometheusReports.ServiceInstance> errorInstances(String selector, String window) {
        Map<String, Double> rates = PrometheusSeries.latestByInstance(
                client.query(MetricQueryRegistry.errorRateByInstance(selector, window)));
        List<PrometheusReports.ServiceInstance> instances = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rates.entrySet()) {
            if (entry.getValue() >= ERROR_RATE_ABNORMAL) {
                instances.add(new PrometheusReports.ServiceInstance(entry.getKey(), round(entry.getValue()), true));
            }
        }
        instances.sort(Comparator.comparingDouble(PrometheusReports.ServiceInstance::errorRate).reversed());
        return capInstances(instances);
    }

    private List<PrometheusReports.ResourceInstance> resourceInstances(String selector) {
        Map<String, Double> cpu = PrometheusSeries.latestByInstance(client.query(MetricQueryRegistry.processCpuByInstance(selector)));
        Map<String, Double> memory = PrometheusSeries.latestByInstance(
                client.query(MetricQueryRegistry.processMemoryByInstance(selector)));
        double median = median(memory.values());
        Map<String, PrometheusReports.ResourceInstance> merged = new LinkedHashMap<>();
        for (String instance : union(cpu, memory)) {
            Double cpuValue = cpu.get(instance);
            Double memoryValue = memory.get(instance);
            boolean cpuHigh = cpuValue != null && cpuValue >= CPU_ABNORMAL;
            boolean memoryHigh = memoryValue != null && median > 0 && memoryValue >= median * 1.5 && memoryValue > median;
            if (!cpuHigh && !memoryHigh) {
                continue;
            }
            merged.put(instance, new PrometheusReports.ResourceInstance(
                    instance,
                    cpuValue == null ? null : round(cpuValue),
                    memoryValue == null ? null : round(memoryValue),
                    true));
        }
        List<PrometheusReports.ResourceInstance> instances = new ArrayList<>(merged.values());
        instances.sort(Comparator.comparingDouble((PrometheusReports.ResourceInstance item) ->
                item.processCpu() == null ? 0 : item.processCpu()).reversed());
        return capInstances(instances);
    }

    private List<PrometheusReports.JvmInstance> jvmInstances(String selector) {
        Map<String, Double> oldGen = PrometheusSeries.latestByInstance(
                client.query(MetricQueryRegistry.jvmOldGenUsageByInstance(selector)));
        List<PrometheusReports.JvmInstance> instances = new ArrayList<>();
        for (Map.Entry<String, Double> entry : oldGen.entrySet()) {
            if (entry.getValue() >= OLD_GEN_ABNORMAL) {
                instances.add(new PrometheusReports.JvmInstance(entry.getKey(), round(entry.getValue()), true));
            }
        }
        instances.sort(Comparator.comparingDouble(PrometheusReports.JvmInstance::oldGenUsage).reversed());
        return capInstances(instances);
    }

    private static PrometheusReports.AlertView toAlert(JsonNode alert) {
        String state = alert.path("state").asText("");
        if (state.equalsIgnoreCase("inactive")) {
            return null;
        }
        JsonNode labels = alert.path("labels");
        String alertName = blankToNull(labels.path("alertname").asText(""));
        if (alertName == null) {
            return null;
        }
        JsonNode annotations = alert.path("annotations");
        String description = blankToNull(annotations.path("description").asText(""));
        if (description == null) {
            description = blankToNull(annotations.path("summary").asText(""));
        }
        return new PrometheusReports.AlertView(
                alertName,
                blankToNull(labels.path("service").asText("")),
                blankToNull(labels.path("instance").asText("")),
                blankToNull(labels.path("severity").asText("")),
                blankToNull(state),
                formatTime(alert.path("activeAt").asText("")),
                clip(description, 300));
    }

    private static String formatTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static boolean allMissing(PrometheusReports.MetricSignal... signals) {
        for (PrometheusReports.MetricSignal signal : signals) {
            if (signal != null) {
                return false;
            }
        }
        return true;
    }

    private static PrometheusReports.MetricSignal orMissing(PrometheusReports.MetricSignal signal) {
        return signal == null ? PrometheusReports.MetricSignal.missing() : signal;
    }

    private static <T> List<T> capInstances(List<T> instances) {
        if (instances.isEmpty()) {
            return null;
        }
        if (instances.size() > MAX_INSTANCES) {
            return List.copyOf(instances.subList(0, MAX_INSTANCES));
        }
        return List.copyOf(instances);
    }

    private static List<String> union(Map<String, Double> left, Map<String, Double> right) {
        Map<String, Boolean> names = new LinkedHashMap<>();
        left.keySet().forEach(name -> names.put(name, true));
        right.keySet().forEach(name -> names.put(name, true));
        return new ArrayList<>(names.keySet());
    }

    private static double median(java.util.Collection<Double> values) {
        List<Double> sorted = values.stream().filter(value -> value != null && Double.isFinite(value)).sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }
}
