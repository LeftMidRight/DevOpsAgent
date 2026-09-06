package org.example.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    @Test
    void rareTermRanksMatchingDocumentFirst() {
        Bm25Index index = new Bm25Index(List.of(
                new Bm25Index.Bm25Document("cpu", List.of("cpu", "使用率", "过高")),
                new Bm25Index.Bm25Document("mem", List.of("内存", "gc", "oom")),
                new Bm25Index.Bm25Document("disk", List.of("磁盘", "空间", "清理"))
        ));
        List<Bm25Index.ScoredId> ranked = index.score(List.of("oom"), 3);
        assertEquals("mem", ranked.get(0).id());
        assertTrue(ranked.get(0).score() > 0);
    }

    @Test
    void emptyQueryReturnsEmptyList() {
        Bm25Index index = new Bm25Index(List.of(
                new Bm25Index.Bm25Document("cpu", List.of("cpu"))
        ));
        assertTrue(index.score(List.of(), 5).isEmpty());
    }

    @Test
    void emptyIndexReturnsEmptyList() {
        assertTrue(new Bm25Index(List.of()).score(List.of("cpu"), 5).isEmpty());
    }
}
