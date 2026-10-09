package org.example.retrieval;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 实验重排：用查询词覆盖率替换排序。返回的 score 是词面覆盖率，不是 RRF 分。
 * 只有 retrieval.reranker.type=lexical 时启用。
 */
@Component
@ConditionalOnProperty(prefix = "retrieval.reranker", name = "type", havingValue = "lexical")
public class LexicalChunkReranker implements ChunkReranker {

    private final ChineseTokenizer tokenizer;

    public LexicalChunkReranker(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public boolean usesRelevanceScore() {
        return true;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> queryTokens = tokenizer.tokenize(query);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            RetrievedChunk candidate = candidates.get(i);
            scored.add(new Scored(candidate, coverage(queryTokens, candidate), i));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::order));
        List<RetrievedChunk> results = new ArrayList<>();
        for (Scored item : scored) {
            if (results.size() >= topK) {
                break;
            }
            item.chunk.setScore((float) item.score);
            results.add(item.chunk);
        }
        return results;
    }

    static double coverage(List<String> queryTokens, RetrievedChunk chunk) {
        if (queryTokens == null || queryTokens.isEmpty()) {
            return 0;
        }
        String haystack = ((chunk.getTitle() == null ? "" : chunk.getTitle()) + "\n"
                + (chunk.getContent() == null ? "" : chunk.getContent()) + "\n"
                + (chunk.getMetadata() == null ? "" : chunk.getMetadata())).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String token : queryTokens) {
            if (token != null && !token.isBlank() && haystack.contains(token.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return (double) hits / queryTokens.size();
    }

    private record Scored(RetrievedChunk chunk, double score, int order) {}
}
