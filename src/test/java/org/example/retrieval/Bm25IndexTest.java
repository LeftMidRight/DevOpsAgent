package org.example.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    @Test
    void rareTermRanksMatchingDocumentFirst() {
        List<Bm25Index.Bm25Document> docs = List.of(
                new Bm25Index.Bm25Document("cpu", List.of("cpu", "使用率", "过高")),
                new Bm25Index.Bm25Document("mem", List.of("内存", "gc", "oom")),
                new Bm25Index.Bm25Document("disk", List.of("磁盘", "空间", "清理"))
        );
        List<Bm25Index.ScoredId> ranked = Bm25Index.score(docs, List.of("oom"), statsOf(docs), 3);
        assertEquals("mem", ranked.get(0).id());
        assertTrue(ranked.get(0).score() > 0);
    }

    @Test
    void emptyQueryReturnsEmptyList() {
        List<Bm25Index.Bm25Document> docs = List.of(new Bm25Index.Bm25Document("cpu", List.of("cpu")));
        assertTrue(Bm25Index.score(docs, List.of(), statsOf(docs), 5).isEmpty());
    }

    @Test
    void emptyIndexReturnsEmptyList() {
        assertTrue(Bm25Index.score(List.of(), List.of("cpu"), new Bm25CorpusStats(1, 1, Map.of("cpu", 1L)), 5).isEmpty());
    }

    @Test
    void duplicateQueryTermsAreCountedOnce() {
        List<Bm25Index.Bm25Document> docs = List.of(new Bm25Index.Bm25Document("cpu", List.of("cpu", "过高")));
        Bm25CorpusStats stats = statsOf(docs);
        double once = Bm25Index.score(docs, List.of("cpu", "过高"), stats, 1).get(0).score();
        double repeated = Bm25Index.score(docs, List.of("cpu", "cpu", "cpu", "过高"), stats, 1).get(0).score();
        assertEquals(once, repeated);
    }

    @Test
    void rareTermHasHigherIdfThanCommonTermUsingGlobalStats() {
        List<Bm25Index.Bm25Document> candidates = List.of(
                new Bm25Index.Bm25Document("rare-doc", List.of("rareterm")),
                new Bm25Index.Bm25Document("common-doc", List.of("commonterm"))
        );
        Bm25CorpusStats stats = new Bm25CorpusStats(100, 1.0, Map.of("rareterm", 1L, "commonterm", 80L));
        List<Bm25Index.ScoredId> ranked = Bm25Index.score(
                candidates, List.of("rareterm", "commonterm"), stats, 2);
        assertEquals("rare-doc", ranked.get(0).id());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    private static Bm25CorpusStats statsOf(List<Bm25Index.Bm25Document> docs) {
        Map<String, Long> df = new java.util.HashMap<>();
        long total = 0;
        for (Bm25Index.Bm25Document doc : docs) {
            total += doc.tokens().size();
            doc.tokens().stream().distinct().forEach(term -> df.merge(term, 1L, Long::sum));
        }
        double avg = docs.isEmpty() ? 0 : (double) total / docs.size();
        return new Bm25CorpusStats(docs.size(), avg, df);
    }
}
