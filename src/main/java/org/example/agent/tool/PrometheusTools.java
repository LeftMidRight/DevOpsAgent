package org.example.agent.tool;

import org.example.observability.ObservabilityAccessException;
import org.example.observability.ObservabilityJson;
import org.example.observability.ObservabilityNames;
import org.example.observability.prometheus.PrometheusQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 按运维诊断意图暴露 Prometheus 只读工具。PromQL 不进入工具参数。
 */
@Component
public class PrometheusTools {

    public static final String QUERY_ACTIVE_ALERTS = "queryActiveAlerts";
    public static final String QUERY_SERVICE_HEALTH = "queryServiceHealth";
    public static final String QUERY_RESOURCE_USAGE = "queryResourceUsage";
    public static final String QUERY_JVM_HEALTH = "queryJvmHealth";
    public static final String CHECK_MONITORING_TARGET = "checkMonitoringTarget";
    public static final Set<String> TOOL_NAMES = Set.of(
            QUERY_ACTIVE_ALERTS,
            QUERY_SERVICE_HEALTH,
            QUERY_RESOURCE_USAGE,
            QUERY_JVM_HEALTH,
            CHECK_MONITORING_TARGET);

    private static final Logger logger = LoggerFactory.getLogger(PrometheusTools.class);

    private final PrometheusQueryService queryService;

    public PrometheusTools(PrometheusQueryService queryService) {
        this.queryService = queryService;
    }

    @Tool(name = QUERY_ACTIVE_ALERTS, description = """
            Read currently firing and pending Prometheus alerts. \
            Pass service to keep alerts for that service label, or omit service to list every active alert. \
            Read-only. Does not accept PromQL or a metric name.""")
    public String queryActiveAlerts(
            @ToolParam(description = "Optional service label. Omit to list all active alerts.", required = false)
            String service) {
        try {
            String validated = ObservabilityNames.optionalService(service);
            logger.info("queryActiveAlerts service={}", validated);
            return ObservabilityJson.write(queryService.queryActiveAlerts(validated));
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("queryActiveAlerts 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }

    @Tool(name = QUERY_SERVICE_HEALTH, description = """
            Inspect request rate, server error rate, and P95/P99 latency in milliseconds for one service. \
            Each present signal has current, average, max and trend. \
            status no_data means that signal has no series; numeric 0 is a real zero; \
            status target_down means Prometheus is not scraping the service. \
            service is required. lookback defaults to 30m, examples 15m or 1h, maximum 6h. instance is optional. \
            Read-only. Does not accept PromQL or a metric name.""")
    public String queryServiceHealth(
            @ToolParam(description = "Service label of the target application, for example payment-service")
            String service,
            @ToolParam(description = "Lookback window such as 30m, 90s or 2h. Default 30m, maximum 6h.", required = false)
            String lookback,
            @ToolParam(description = "Optional instance label, for example 10.0.0.12:8080", required = false)
            String instance) {
        try {
            ObservabilityNames.Lookback window = ObservabilityNames.lookback(lookback);
            String validatedService = ObservabilityNames.requireService(service);
            String validatedInstance = ObservabilityNames.optionalInstance(instance);
            logger.info("queryServiceHealth service={} lookback={} instance={}",
                    validatedService, window.label(), validatedInstance);
            return ObservabilityJson.write(queryService.queryServiceHealth(
                    validatedService, validatedInstance, window.label(), window.duration()));
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("queryServiceHealth 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }

    @Tool(name = QUERY_RESOURCE_USAGE, description = """
            Inspect process CPU (ratio from 0 to 1) and resident memory in bytes for one service. \
            Returns current, average, max, trend, and instances with abnormal CPU or memory. \
            status no_data means the series is absent; numeric 0 is a real zero; \
            status target_down means the scrape target is down. \
            service is required. lookback defaults to 30m, maximum 6h. instance is optional. \
            Read-only. Does not accept PromQL or a metric name.""")
    public String queryResourceUsage(
            @ToolParam(description = "Service label of the target application")
            String service,
            @ToolParam(description = "Lookback window such as 30m, 90s or 2h. Default 30m, maximum 6h.", required = false)
            String lookback,
            @ToolParam(description = "Optional instance label", required = false)
            String instance) {
        try {
            ObservabilityNames.Lookback window = ObservabilityNames.lookback(lookback);
            String validatedService = ObservabilityNames.requireService(service);
            String validatedInstance = ObservabilityNames.optionalInstance(instance);
            logger.info("queryResourceUsage service={} lookback={} instance={}",
                    validatedService, window.label(), validatedInstance);
            return ObservabilityJson.write(queryService.queryResourceUsage(
                    validatedService, validatedInstance, window.label(), window.duration()));
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("queryResourceUsage 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }

    @Tool(name = QUERY_JVM_HEALTH, description = """
            Inspect JVM heap usage, old generation usage, GC rate per second, GC pause P99 in milliseconds, \
            and live thread count for one Java service. \
            status no_data means the series is absent; numeric 0 is a real zero; \
            status target_down means the scrape target is down. \
            service is required. lookback defaults to 30m, maximum 6h. instance is optional. \
            Read-only. Does not accept PromQL or a metric name.""")
    public String queryJvmHealth(
            @ToolParam(description = "Service label of the Java application")
            String service,
            @ToolParam(description = "Lookback window such as 30m, 90s or 2h. Default 30m, maximum 6h.", required = false)
            String lookback,
            @ToolParam(description = "Optional instance label", required = false)
            String instance) {
        try {
            ObservabilityNames.Lookback window = ObservabilityNames.lookback(lookback);
            String validatedService = ObservabilityNames.requireService(service);
            String validatedInstance = ObservabilityNames.optionalInstance(instance);
            logger.info("queryJvmHealth service={} lookback={} instance={}",
                    validatedService, window.label(), validatedInstance);
            return ObservabilityJson.write(queryService.queryJvmHealth(
                    validatedService, validatedInstance, window.label(), window.duration()));
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("queryJvmHealth 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }

    @Tool(name = CHECK_MONITORING_TARGET, description = """
            Check whether Prometheus is successfully scraping a service. \
            Use this when metrics are missing, to distinguish a quiet service from a down scrape target. \
            Returns target health, last scrape time, scrape URL and last error. \
            service is required. instance is optional. Read-only. Does not accept PromQL.""")
    public String checkMonitoringTarget(
            @ToolParam(description = "Service label of the target application")
            String service,
            @ToolParam(description = "Optional instance label", required = false)
            String instance) {
        try {
            String validatedService = ObservabilityNames.requireService(service);
            String validatedInstance = ObservabilityNames.optionalInstance(instance);
            logger.info("checkMonitoringTarget service={} instance={}", validatedService, validatedInstance);
            return ObservabilityJson.write(queryService.checkMonitoringTarget(validatedService, validatedInstance));
        } catch (IllegalArgumentException e) {
            return ObservabilityJson.failure("invalid_argument", e.getMessage());
        } catch (ObservabilityAccessException e) {
            logger.warn("checkMonitoringTarget 失败: {}", e.getMessage());
            return ObservabilityJson.failure(e.errorType(), e.getMessage());
        }
    }
}
