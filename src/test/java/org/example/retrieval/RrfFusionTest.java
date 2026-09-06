package org.example.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void bothPathsBeatSinglePathTopHit() {
        List<String> vector = List.of("A", "C", "B");
        List<String> bm25 = List.of("A", "C");
        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(List.of(vector, bm25), 60, 3);
        assertEquals("A", fused.get(0).id());
        assertEquals("C", fused.get(1).id());
        assertEquals("B", fused.get(2).id());
        assertTrue(Math.abs(fused.get(0).score() - (1.0 / 61 + 1.0 / 61)) < 1e-9);
    }

    @Test
    void missingFromOnePathContributesZero() {
        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(
                List.of(List.of("only-vec"), List.of("only-bm25")), 60, 2);
        assertEquals(2, fused.size());
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-9);
        assertEquals(fused.get(0).score(), fused.get(1).score(), 1e-9);
    }
}
