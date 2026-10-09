package org.example.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认重排：保持 RRF 顺序和 RRF score，只截断到 topK。
 */
@Component
@ConditionalOnProperty(prefix = "retrieval.reranker", name = "type", havingValue = "none", matchIfMissing = true)
public class NoOpChunkReranker implements ChunkReranker {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        int limit = Math.min(topK, candidates.size());
        return List.copyOf(candidates.subList(0, limit));
    }
}
