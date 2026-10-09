package org.example.observability;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条时间序列压成 current / average / max / trend，避免把原始采样交给模型。
 */
public final class SeriesMath {

    public record Summary(double current, double average, double max, String trend) {
    }

    private SeriesMath() {
    }

    public static Summary summarize(List<Double> values) {
        List<Double> finite = new ArrayList<>();
        if (values != null) {
            for (Double value : values) {
                if (value != null && Double.isFinite(value)) {
                    finite.add(value);
                }
            }
        }
        if (finite.isEmpty()) {
            return null;
        }
        double current = finite.get(finite.size() - 1);
        double max = current;
        double sum = 0;
        for (double value : finite) {
            sum += value;
            max = Math.max(max, value);
        }
        return new Summary(round(current), round(sum / finite.size()), round(max), trend(finite));
    }

    static String trend(List<Double> finite) {
        if (finite.size() < 2) {
            return "unknown";
        }
        double first = finite.get(0);
        double last = finite.get(finite.size() - 1);
        double span = Math.max(Math.abs(first), Math.abs(last));
        if (span == 0 || Math.abs(last - first) <= span * 0.2) {
            return "stable";
        }
        return last > first ? "rising" : "falling";
    }

    private static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
