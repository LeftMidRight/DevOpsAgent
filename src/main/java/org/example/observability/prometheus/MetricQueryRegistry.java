package org.example.observability.prometheus;

/**
 * 诊断意图到固定 PromQL 模板的映射。指标名只出现在这里，不接受模型传入。
 *
 * <p>被观测服务需要暴露 Micrometer 指标，并用 {@code service} 标签标识业务。
 * 延迟分位数依赖 {@code http_server_requests_seconds_bucket}，GC 停顿分位数依赖
 * {@code jvm_gc_pause_seconds_bucket}。
 */
public final class MetricQueryRegistry {

    private MetricQueryRegistry() {
    }

    public static String requestRate(String selector, String window) {
        return "sum(rate(http_server_requests_seconds_count" + selector + "[" + window + "]))";
    }

    public static String errorRate(String selector, String window) {
        String errors = withExtra(selector, "outcome=\"SERVER_ERROR\"");
        return "((sum(rate(http_server_requests_seconds_count" + errors + "[" + window + "])) or vector(0))"
                + "/clamp_min(sum(rate(http_server_requests_seconds_count" + selector + "[" + window + "])), 1e-9))";
    }

    public static String errorRateByInstance(String selector, String window) {
        String errors = withExtra(selector, "outcome=\"SERVER_ERROR\"");
        return "(sum by (instance) (rate(http_server_requests_seconds_count" + errors + "[" + window + "]))"
                + "/clamp_min(sum by (instance) (rate(http_server_requests_seconds_count" + selector + "[" + window + "])), 1e-9))";
    }

    public static String latencyQuantile(String quantile, String selector, String window) {
        return "histogram_quantile(" + quantile + ", sum by (le) (rate(http_server_requests_seconds_bucket"
                + selector + "[" + window + "])))";
    }

    public static String processCpu(String selector) {
        return "avg(process_cpu_usage" + selector + ")";
    }

    public static String processCpuByInstance(String selector) {
        return "max by (instance) (process_cpu_usage" + selector + ")";
    }

    public static String processMemory(String selector) {
        return "avg(process_resident_memory_bytes" + selector + ")";
    }

    public static String processMemoryByInstance(String selector) {
        return "max by (instance) (process_resident_memory_bytes" + selector + ")";
    }

    public static String jvmHeapUsage(String selector) {
        String heap = withExtra(selector, "area=\"heap\"");
        return "sum(jvm_memory_used_bytes" + heap + ")/clamp_min(sum(jvm_memory_max_bytes" + heap + "), 1)";
    }

    public static String jvmOldGenUsage(String selector) {
        String oldGen = withExtra(selector, "area=\"heap\",id=~\".*Old Gen|Tenured Gen\"");
        return "sum(jvm_memory_used_bytes" + oldGen + ")/clamp_min(sum(jvm_memory_max_bytes" + oldGen + "), 1)";
    }

    public static String jvmOldGenUsageByInstance(String selector) {
        String oldGen = withExtra(selector, "area=\"heap\",id=~\".*Old Gen|Tenured Gen\"");
        return "(sum by (instance) (jvm_memory_used_bytes" + oldGen + ")"
                + "/clamp_min(sum by (instance) (jvm_memory_max_bytes" + oldGen + "), 1))";
    }

    public static String gcRate(String selector, String window) {
        return "sum(rate(jvm_gc_pause_seconds_count" + selector + "[" + window + "]))";
    }

    public static String gcPauseP99(String selector, String window) {
        return "histogram_quantile(0.99, sum by (le) (rate(jvm_gc_pause_seconds_bucket"
                + selector + "[" + window + "])))";
    }

    public static String threadCount(String selector) {
        return "sum(jvm_threads_live_threads" + selector + ")";
    }

    static String withExtra(String selector, String extra) {
        int end = selector.lastIndexOf('}');
        return selector.substring(0, end) + "," + extra + "}";
    }
}
