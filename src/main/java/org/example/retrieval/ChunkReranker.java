package org.example.retrieval;

import java.util.List;

public interface ChunkReranker {

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK);

    /**
     * true 表示返回的 score 是相关性分数，可以和 minScore 比较。
     * RRF 分数不是绝对相关性，默认不能用来判断无结果。
     */
    default boolean usesRelevanceScore() {
        return false;
    }
}
