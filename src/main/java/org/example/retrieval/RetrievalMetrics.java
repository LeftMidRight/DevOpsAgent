package org.example.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    public static double hitAt(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty() || rankedIds == null || k <= 0) {
            return 0;
        }
        int limit = Math.min(k, rankedIds.size());
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                return 1;
            }
        }
        return 0;
    }

    public static double recallAt(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (relevantIds == null || relevantIds.isEmpty() || k <= 0) {
            return 0;
        }
        int limit = Math.min(k, rankedIds == null ? 0 : rankedIds.size());
        java.util.Set<String> hits = new java.util.HashSet<>();
        for (int i = 0; i < limit; i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                hits.add(rankedIds.get(i));
            }
        }
        return (double) hits.size() / relevantIds.size();
    }

    public static double mrr(List<String> rankedIds, Set<String> relevantIds) {
        if (rankedIds == null || relevantIds == null || relevantIds.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < rankedIds.size(); i++) {
            if (relevantIds.contains(rankedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    public static String summarize(List<QueryResult> results) {
        int count = results.size();
        double hit1 = average(results, 1, true);
        double hit3 = average(results, 3, true);
        double hit5 = average(results, 5, true);
        double recall5 = average(results, 5, false);
        double mrr = results.stream().mapToDouble(QueryResult::mrr).average().orElse(0);
        List<String> misses = new ArrayList<>();
        for (QueryResult result : results) {
            if (result.hitAt5() == 0) {
                misses.add(result.id());
            }
        }
        return "Query count=" + count
                + " HitRate@1=" + format(hit1)
                + " HitRate@3=" + format(hit3)
                + " HitRate@5=" + format(hit5)
                + " Recall@5=" + format(recall5)
                + " MRR=" + format(mrr)
                + " MISS=" + misses;
    }

    private static double average(List<QueryResult> results, int k, boolean hit) {
        return results.stream()
                .mapToDouble(result -> hit ? result.hitAt(k) : result.recallAt(k))
                .average()
                .orElse(0);
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }

    public record QueryResult(String id, List<String> rankedIds, Set<String> relevantIds) {
        public double hitAt(int k) {
            return RetrievalMetrics.hitAt(rankedIds, relevantIds, k);
        }

        public double hitAt5() {
            return hitAt(5);
        }

        public double recallAt(int k) {
            return RetrievalMetrics.recallAt(rankedIds, relevantIds, k);
        }

        public double mrr() {
            return RetrievalMetrics.mrr(rankedIds, relevantIds);
        }
    }
}
