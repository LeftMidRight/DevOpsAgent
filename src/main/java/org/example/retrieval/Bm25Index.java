package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class Bm25Index {

    public record Bm25Document(String id, List<String> tokens) {}
    public record ScoredId(String id, double score) {}

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25Index() {}

    public static List<ScoredId> score(
            List<Bm25Document> candidates,
            List<String> queryTokens,
            Bm25CorpusStats stats,
            int topN) {
        if (candidates == null || candidates.isEmpty() || stats == null || stats.documentCount() <= 0
                || queryTokens == null || queryTokens.isEmpty() || topN <= 0) {
            return List.of();
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(queryTokens));
        double avgdl = Math.max(stats.averageDocumentLength(), 1e-9);
        long n = stats.documentCount();
        List<ScoredId> scored = new ArrayList<>();
        for (Bm25Document document : candidates) {
            double score = scoreDocument(document, terms, stats, n, avgdl);
            if (score > 0) {
                scored.add(new ScoredId(document.id(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed()
                .thenComparing(ScoredId::id));
        return scored.subList(0, Math.min(topN, scored.size()));
    }

    private static double scoreDocument(
            Bm25Document document,
            List<String> terms,
            Bm25CorpusStats stats,
            long n,
            double avgdl) {
        Map<String, Integer> tf = new HashMap<>();
        List<String> tokens = document.tokens() == null ? List.of() : document.tokens();
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }
        int dl = Math.max(tokens.size(), 1);
        double score = 0;
        for (String term : terms) {
            int frequency = tf.getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            long df = stats.documentFrequency(term);
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            double denominator = frequency + K1 * (1 - B + B * dl / avgdl);
            score += idf * frequency * (K1 + 1) / denominator;
        }
        return score;
    }
}
