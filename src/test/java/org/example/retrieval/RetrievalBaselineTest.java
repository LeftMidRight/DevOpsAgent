package org.example.retrieval;

import org.example.chunking.MarkdownChunker;
import org.example.chunking.MarkdownParser;
import org.example.config.DocumentChunkConfig;
import org.example.context.TokenEstimator;
import org.example.service.ChunkDraft;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalBaselineTest {

    @Test
    void headingContextRanksTheMatchingSectionFirst() {
        List<ChunkDraft> chunks = new MarkdownChunker(new TokenEstimator(), new DocumentChunkConfig())
                .chunk(new MarkdownParser().parse("""
                        # MySQL
                        检查连接池大小
                        # Redis
                        检查连接池大小
                        # HTTP Client
                        检查连接池大小
                        """), "pools.md");
        ChineseTokenizer tokenizer = new ChineseTokenizer();
        LexicalChunkReranker reranker = new LexicalChunkReranker(tokenizer);
        List<RetrievedChunk> candidates = new ArrayList<>();
        for (ChunkDraft chunk : chunks) {
            RetrievedChunk retrieved = new RetrievedChunk();
            retrieved.setId(chunk.title());
            retrieved.setTitle(chunk.title());
            retrieved.setContent(chunk.retrievalText());
            retrieved.setMetadata(String.join(" > ", chunk.headingPath()));
            candidates.add(retrieved);
        }
        List<RetrievedChunk> ranked = reranker.rerank("MySQL 检查连接池大小", candidates, 3);
        List<String> ids = ranked.stream().map(RetrievedChunk::getId).toList();
        assertEquals(1, RetrievalMetrics.hitAt(ids, Set.of("MySQL"), 1));
        assertEquals(1, RetrievalMetrics.recallAt(ids, Set.of("MySQL"), 3));
        assertEquals(1, RetrievalMetrics.mrr(ids, Set.of("MySQL")));
        assertEquals("MySQL", ids.get(0));
    }

    @Test
    void reranksCpuThrottleAheadOfOtherCpuChunks() {
        LexicalChunkReranker reranker = new LexicalChunkReranker(new ChineseTokenizer());
        List<RetrievedChunk> candidates = List.of(
                chunk("usage", "CPU usage 持续升高"),
                chunk("throttle", "CPU throttle 说明容器达到了 CPU limit"),
                chunk("request", "CPU request 只影响调度"),
                chunk("limit", "CPU limit 是容器可用的上限"));
        List<RetrievedChunk> ranked = reranker.rerank("CPU throttle", candidates, 4);
        assertEquals("throttle", ranked.get(0).getId());
        assertTrue(ranked.get(0).getScore() > ranked.get(1).getScore());
    }

    @Test
    void dropsCandidatesBelowMinScore() {
        LexicalChunkReranker reranker = new LexicalChunkReranker(new ChineseTokenizer());
        RetrievedChunk unrelated = chunk("other", "磁盘空间不足");
        List<RetrievedChunk> ranked = reranker.rerank("CPU throttle", List.of(unrelated), 3);
        List<RetrievedChunk> kept = ranked.stream()
                .filter(chunk -> chunk.getScore() >= 0.5f)
                .sorted(Comparator.comparing(RetrievedChunk::getScore).reversed())
                .toList();
        assertTrue(kept.isEmpty());
        assertEquals(0, RetrievalMetrics.hitAt(List.of(), Set.of("throttle"), 5));
    }

    @Test
    void recallCountsEveryRelevantId() {
        List<String> ranked = List.of("A", "X", "B");
        assertEquals(1, RetrievalMetrics.hitAt(ranked, Set.of("A", "B", "C"), 1));
        assertEquals(1.0 / 3, RetrievalMetrics.recallAt(ranked, Set.of("A", "B", "C"), 1));
        assertEquals(2.0 / 3, RetrievalMetrics.recallAt(ranked, Set.of("A", "B", "C"), 5));
    }

    private static RetrievedChunk chunk(String id, String content) {
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setTitle(content);
        return chunk;
    }
}
