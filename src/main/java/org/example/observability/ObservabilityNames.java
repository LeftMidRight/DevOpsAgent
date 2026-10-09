package org.example.observability;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 观测工具的参数边界。service / instance 只允许标签安全字符，避免拼进 PromQL 或 LogQL。
 */
public final class ObservabilityNames {

    public static final Duration DEFAULT_LOOKBACK = Duration.ofMinutes(30);
    public static final Duration MAX_LOOKBACK = Duration.ofHours(6);
    public static final String DEFAULT_LOOKBACK_LABEL = "30m";

    private static final Pattern LOOKBACK = Pattern.compile("^([1-9]\\d{0,4})(s|m|h)$");
    private static final Pattern SERVICE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]{0,62}$");
    private static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$");
    private static final int MAX_KEYWORD_CHARS = 128;

    private ObservabilityNames() {
    }

    public record Lookback(Duration duration, String label) {
    }

    public static String requireService(String service) {
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service 不能为空");
        }
        return validate(service.trim(), SERVICE, "service");
    }

    /** 空白视为不筛选。非空白仍做字符校验。 */
    public static String optionalService(String service) {
        if (service == null || service.isBlank()) {
            return null;
        }
        return validate(service.trim(), SERVICE, "service");
    }

    public static String optionalInstance(String instance) {
        if (instance == null || instance.isBlank()) {
            return null;
        }
        return validate(instance.trim(), INSTANCE, "instance");
    }

    public static Lookback lookback(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Lookback(DEFAULT_LOOKBACK, DEFAULT_LOOKBACK_LABEL);
        }
        String text = raw.trim().toLowerCase();
        Matcher matcher = LOOKBACK.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("lookback 必须是类似 30m、90s、2h 的时间范围");
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("lookback 单位只能是 s、m 或 h");
        };
        if (duration.compareTo(MAX_LOOKBACK) > 0) {
            throw new IllegalArgumentException("lookback 不能超过 6h");
        }
        return new Lookback(duration, text);
    }

    public static String rateWindow(Duration lookback) {
        long minutes = Math.max(1, lookback.toMinutes());
        return Math.min(5, minutes) + "m";
    }

    public static String step(Duration lookback) {
        long seconds = Math.max(1, lookback.toSeconds());
        long step = seconds < 15 ? seconds : Math.max(15, seconds / 20);
        return step + "s";
    }

    public static String selector(String service, String instance) {
        StringBuilder labels = new StringBuilder("{service=\"").append(service).append('"');
        if (instance != null) {
            labels.append(",instance=\"").append(instance).append('"');
        }
        return labels.append('}').toString();
    }

    public static String optionalLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toUpperCase();
        if (!normalized.equals("ERROR") && !normalized.equals("WARN") && !normalized.equals("INFO")
                && !normalized.equals("DEBUG") && !normalized.equals("TRACE")) {
            throw new IllegalArgumentException("level 只能是 ERROR、WARN、INFO、DEBUG 或 TRACE");
        }
        return normalized;
    }

    public static String optionalKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String text = keyword.trim();
        if (text.length() > MAX_KEYWORD_CHARS) {
            throw new IllegalArgumentException("keyword 不能超过 " + MAX_KEYWORD_CHARS + " 个字符");
        }
        if (text.codePoints().anyMatch(cp -> cp < 0x20 || cp == 0x7f)) {
            throw new IllegalArgumentException("keyword 不能包含控制字符");
        }
        return text;
    }

    public static String escapeLogqlLiteral(String keyword) {
        return keyword.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String validate(String value, Pattern pattern, String name) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " 含有非法字符或长度超限");
        }
        return value;
    }
}
