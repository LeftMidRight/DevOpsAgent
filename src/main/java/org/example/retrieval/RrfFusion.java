package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RrfFusion {

    private RrfFusion() {}

    public static List<Bm25Index.ScoredId> fuse(List<List<String>> rankedLists, int rrfK, int topK) {
        Map<String, Double> scores = new HashMap<>();
        for (List<String> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i);
                int rank = i + 1;
                scores.merge(id, 1.0 / (rrfK + rank), Double::sum);
            }
        }
        List<Bm25Index.ScoredId> out = new ArrayList<>();
        scores.forEach((id, s) -> out.add(new Bm25Index.ScoredId(id, s)));
        out.sort(Comparator.comparingDouble(Bm25Index.ScoredId::score).reversed()
                .thenComparing(Bm25Index.ScoredId::id));
        return out.subList(0, Math.min(topK, out.size()));
    }
}
