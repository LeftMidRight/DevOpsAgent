package org.example.service;

import org.example.config.RetrievalProperties;
import org.example.repository.ChunkRepository;
import org.example.retrieval.Bm25Index;
import org.example.retrieval.ChunkRecord;
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

    public HybridRetrievalService(
            ChunkRepository chunkRepository,
            ChineseTokenizer chineseTokenizer,
            VectorEmbeddingService embeddingService,
            RetrievalProperties retrievalProperties) {
        this.chunkRepository = chunkRepository;
        this.chineseTokenizer = chineseTokenizer;
        this.embeddingService = embeddingService;
        this.retrievalProperties = retrievalProperties;
    }

    public List<RetrievedChunk> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }

        logger.info("Hybrid search: query='{}', topK={}", query, topK);

        List<String> queryTokens = chineseTokenizer.tokenize(query);
        List<Float> queryVector = embeddingService.generateQueryVector(query);

        List<ChunkRecord> allChunks = chunkRepository.findAll();
        Map<String, ChunkRecord> byId = new HashMap<>();
        List<Bm25Index.Bm25Document> bm25Docs = new ArrayList<>();
        for (ChunkRecord chunk : allChunks) {
            String id = chunk.id().toString();
            byId.put(id, chunk);
            bm25Docs.add(new Bm25Index.Bm25Document(id, chunk.tokens()));
        }

        Bm25Index bm25Index = new Bm25Index(bm25Docs);
        List<String> bm25Ids = bm25Index.score(queryTokens, retrievalProperties.getBm25TopN()).stream()
                .map(Bm25Index.ScoredId::id)
                .collect(Collectors.toList());

        List<String> vectorIds = chunkRepository
                .searchByEmbedding(queryVector, retrievalProperties.getVectorTopN()).stream()
                .map(chunk -> chunk.id().toString())
                .collect(Collectors.toList());

        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(
                List.of(vectorIds, bm25Ids),
                retrievalProperties.getRrfK(),
                topK);

        List<RetrievedChunk> results = new ArrayList<>();
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
            results.add(result);
        }

        logger.info("Hybrid search complete: {} results", results.size());
        return results;
    }
}
