package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bm25Index {

    public record Bm25Document(String id, List<String> tokens) {}
    public record ScoredId(String id, double score) {}

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Bm25Document> docs;
    private final Map<String, Integer> df = new HashMap<>();
    private final double avgdl;
    private final int n;

    public Bm25Index(List<Bm25Document> docs) {
        this.docs = List.copyOf(docs);
        this.n = docs.size();
        double lenSum = 0;
        for (Bm25Document d : docs) {
            lenSum += d.tokens().size();
            d.tokens().stream().distinct().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        this.avgdl = n == 0 ? 0 : lenSum / n;
    }

    public List<ScoredId> score(List<String> queryTokens, int topN) {
        if (n == 0 || queryTokens == null || queryTokens.isEmpty() || topN <= 0) {
            return List.of();
        }
        List<ScoredId> scored = new ArrayList<>();
        for (Bm25Document d : docs) {
            double s = 0;
            Map<String, Integer> tf = new HashMap<>();
            for (String t : d.tokens()) {
                tf.merge(t, 1, Integer::sum);
            }
            int dl = Math.max(d.tokens().size(), 1);
            for (String q : queryTokens) {
                int f = tf.getOrDefault(q, 0);
                if (f == 0) {
                    continue;
                }
                int dft = df.getOrDefault(q, 0);
                double idf = Math.log(1.0 + (n - dft + 0.5) / (dft + 0.5));
                double denom = f + K1 * (1 - B + B * dl / Math.max(avgdl, 1e-9));
                s += idf * f * (K1 + 1) / denom;
            }
            if (s > 0) {
                scored.add(new ScoredId(d.id(), s));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }
}
