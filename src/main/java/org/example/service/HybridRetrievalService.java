package org.example.service;

import org.example.config.RetrievalProperties;
import org.example.repository.ChunkRepository;
import org.example.retrieval.Bm25CorpusStats;
import org.example.retrieval.Bm25Index;
import org.example.retrieval.ChunkRecord;
import org.example.retrieval.ChunkReranker;
import org.example.retrieval.ChineseTokenizer;
import org.example.retrieval.RetrievedChunk;
import org.example.retrieval.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HybridRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(HybridRetrievalService.class);

    private final ChunkRepository chunkRepository;
    private final ChineseTokenizer chineseTokenizer;
    private final VectorEmbeddingService embeddingService;
    private final RetrievalProperties retrievalProperties;
    private final ChunkReranker chunkReranker;

    public HybridRetrievalService(
            ChunkRepository chunkRepository,
            ChineseTokenizer chineseTokenizer,
            VectorEmbeddingService embeddingService,
            RetrievalProperties retrievalProperties,
            ChunkReranker chunkReranker) {
        this.chunkRepository = chunkRepository;
        this.chineseTokenizer = chineseTokenizer;
        this.embeddingService = embeddingService;
        this.retrievalProperties = retrievalProperties;
        this.chunkReranker = chunkReranker;
    }

    public List<RetrievedChunk> search(String query, int topK) {
        return search(query, topK, chunkReranker);
    }

    public List<RetrievedChunk> search(String query, int topK, ChunkReranker reranker) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        logger.info("Hybrid search: query='{}', topK={}", query, topK);

        List<String> queryTokens = chineseTokenizer.tokenize(query);
        List<Float> queryVector = embeddingService.generateQueryVector(query);

        List<String> distinctQueryTokens = queryTokens.stream().distinct().toList();
        Bm25CorpusStats corpusStats = chunkRepository.loadCorpusStats(distinctQueryTokens);
        List<Bm25Index.Bm25Document> bm25Docs = chunkRepository.findBm25Candidates(distinctQueryTokens);
        List<String> bm25Ids = Bm25Index.score(
                        bm25Docs, distinctQueryTokens, corpusStats, retrievalProperties.getBm25TopN()).stream()
                .map(Bm25Index.ScoredId::id)
                .collect(Collectors.toList());

        List<String> vectorIds = chunkRepository.searchIdsByEmbedding(
                queryVector, retrievalProperties.getVectorTopN());

        int candidateLimit = Math.max(topK, retrievalProperties.getCandidateTopN());
        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(
                List.of(vectorIds, bm25Ids),
                retrievalProperties.getRrfK(),
                candidateLimit);

        Map<String, ChunkRecord> byId = new HashMap<>();
        for (ChunkRecord chunk : chunkRepository.findByIds(fused.stream().map(Bm25Index.ScoredId::id).toList())) {
            byId.put(chunk.id().toString(), chunk);
        }
        List<RetrievedChunk> candidates = new ArrayList<>();
        for (Bm25Index.ScoredId scored : fused) {
            ChunkRecord chunk = byId.get(scored.id());
            if (chunk == null) {
                continue;
            }
            RetrievedChunk result = new RetrievedChunk();
            result.setId(scored.id());
            result.setFileName(chunk.fileName());
            result.setTitle(chunk.title());
            result.setContent(chunk.content());
            result.setScore((float) scored.score());
            result.setMetadata(chunk.metadataJson());
            candidates.add(result);
        }

        ChunkReranker activeReranker = reranker == null ? chunkReranker : reranker;
        List<RetrievedChunk> reranked = activeReranker.rerank(query, candidates, topK);
        List<RetrievedChunk> results = new ArrayList<>();
        for (RetrievedChunk chunk : reranked) {
            if (!activeReranker.usesRelevanceScore() || chunk.getScore() >= retrievalProperties.getMinScore()) {
                results.add(chunk);
            }
        }
        logger.info("Hybrid search complete: {} results", results.size());
        return results;
    }
}
