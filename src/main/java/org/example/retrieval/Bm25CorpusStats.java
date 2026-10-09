package org.example.retrieval;

import java.util.Map;

/**
 * 全库 BM25 统计。N、平均文档长度和 DF 不能从本次查询的候选集推导。
 */
public record Bm25CorpusStats(
        long documentCount,
        double averageDocumentLength,
        Map<String, Long> documentFrequencyByTerm
) {
    public Bm25CorpusStats {
        documentFrequencyByTerm = documentFrequencyByTerm == null
                ? Map.of()
                : Map.copyOf(documentFrequencyByTerm);
    }

    public long documentFrequency(String term) {
        return documentFrequencyByTerm.getOrDefault(term, 0L);
    }
}
