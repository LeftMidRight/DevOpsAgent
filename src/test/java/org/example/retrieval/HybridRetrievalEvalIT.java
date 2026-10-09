package org.example.retrieval;

import tools.jackson.databind.json.JsonMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.example.config.DocumentChunkConfig;
import org.example.config.MinioProperties;
import org.example.config.RetrievalProperties;
import org.example.config.VolcengineProperties;
import org.example.service.DocumentIngestionService;
import org.example.service.DocumentUploadService;
import org.example.service.HybridRetrievalService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("rag-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridRetrievalEvalIT {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private DocumentUploadService documentUploadService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired
    private DocumentChunkConfig chunkConfig;

    @Autowired
    private VolcengineProperties volcengineProperties;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private MinioClient minioClient;

    private GoldSet goldSet;
    private long publicDocumentsBefore;
    private int devBucketObjectsBefore;

    public static boolean embeddingConfigured() {
        return present("VOLCENGINE_API_KEY") || present("ARK_API_KEY");
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() && !"your-api-key-here".equals(value);
    }

    @BeforeAll
    void setUp() {
        if (!embeddingConfigured()) {
            if (Boolean.getBoolean("rag.eval.required")) {
                fail("mvn verify -P rag-eval 需要配置 VOLCENGINE_API_KEY 或 ARK_API_KEY");
            }
            org.junit.jupiter.api.Assumptions.abort("未配置 VOLCENGINE_API_KEY 或 ARK_API_KEY");
        }
        assertEvalIsolation();
        publicDocumentsBefore = countTable("public", "knowledge_documents");
        devBucketObjectsBefore = countBucketObjects("superbiz-documents");
        resetEvalSchema();
        uploadAiopsDocs();
        assertEvalDataLandedApartFromDev();
        try (InputStream in = getClass().getResourceAsStream("/retrieval/gold-set.json")) {
            goldSet = new JsonMapper().readValue(in, GoldSet.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load gold-set.json", e);
        }
    }

    private void assertEvalIsolation() {
        assertEquals("superbiz-rag-eval", minioProperties.getBucket());
        String searchPath = jdbcTemplate.queryForObject("SHOW search_path", String.class);
        String normalizedPath = searchPath == null ? "" : searchPath.replace(" ", "").trim();
        assertTrue(normalizedPath.startsWith("rag_eval,public"),
                "search_path 必须优先 rag_eval 并保留 public，实际: " + searchPath);
        Integer publicVector = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM pg_type t
                JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE t.typname = 'vector' AND n.nspname = 'public'
                """, Integer.class);
        assertEquals(1, publicVector, "pgvector 应安装在 public，供 search_path 解析");
        Integer evalTables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'rag_eval'
                  AND table_name IN ('document_chunks', 'knowledge_documents')
                """, Integer.class);
        assertEquals(2, evalTables);
    }

    private void assertEvalDataLandedApartFromDev() {
        Long evalDocs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rag_eval.knowledge_documents", Long.class);
        assertTrue(evalDocs != null && evalDocs > 0, "Eval 语料应写入 rag_eval");
        assertEquals(publicDocumentsBefore, countTable("public", "knowledge_documents"));
        assertEquals(devBucketObjectsBefore, countBucketObjects("superbiz-documents"));
        assertTrue(countBucketObjects("superbiz-rag-eval") > 0, "Eval 对象应写入 superbiz-rag-eval");
    }

    private long countTable(String schema, String table) {
        Integer exists = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = ? AND table_name = ?
                """, Integer.class, schema, table);
        if (exists == null || exists == 0) {
            return 0;
        }
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + schema + "." + table, Long.class);
        return count == null ? 0 : count;
    }

    private int countBucketObjects(String bucket) {
        try {
            if (!minioClient.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucket).build())) {
                return 0;
            }
            int count = 0;
            for (Result<Item> item : minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                item.get();
                count++;
            }
            return count;
        } catch (Exception e) {
            throw new RuntimeException("无法统计 MinIO bucket: " + bucket, e);
        }
    }

    private void resetEvalSchema() {
        jdbcTemplate.execute("TRUNCATE TABLE rag_eval.document_chunks, rag_eval.knowledge_documents");
    }

    private void uploadAiopsDocs() {
        Path docsDir = Paths.get("aiops-docs");
        try (Stream<Path> files = Files.list(docsDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(this::upload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare eval corpus", e);
        }
    }

    private void upload(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            var summary = documentUploadService.uploadDocument(
                    file.getFileName().toString(), Files.size(file), in);
            var indexed = documentIngestionService.ingest(UUID.fromString(summary.id()));
            if (!"INDEXED".equals(indexed.status())) {
                fail(indexed.fileName() + " 索引失败: " + indexed.errorMessage());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload " + file, e);
        }
    }

    @Test
    void retrievalBaseline() throws IOException {
        assertEquals(48, goldSet.queries.size());
        String parameters = String.format(
                "queryCount=%d vectorTopN=%d bm25TopN=%d candidateTopN=%d rrfK=%d targetTokens=%d maxTokens=%d overlapTokens=%d embedding=%s",
                goldSet.queries.size(),
                retrievalProperties.getVectorTopN(),
                retrievalProperties.getBm25TopN(),
                retrievalProperties.getCandidateTopN(),
                retrievalProperties.getRrfK(),
                chunkConfig.getTargetTokens(),
                chunkConfig.getMaxTokens(),
                chunkConfig.getOverlapTokens(),
                volcengineProperties.getEmbedding().getModel());
        System.out.println(parameters);
        Benchmark rrf = evaluate(new NoOpChunkReranker());
        Benchmark lexical = evaluate(new LexicalChunkReranker(new ChineseTokenizer()));
        String rrfReport = formatBenchmark("RRF-only", "none", rrf);
        String lexicalReport = formatBenchmark("RRF+lexical", "lexical", lexical);
        System.out.println(rrfReport);
        System.out.println(lexicalReport);
        Path report = Paths.get("docs", "rag-eval-baseline.md");
        Files.writeString(report, """
                # RAG eval baseline

                ```
                %s

                %s

                %s
                ```
                """.formatted(parameters, rrfReport, lexicalReport));
        double hitAt5 = rrf.answered().stream().mapToDouble(result -> result.hitAt(5)).average().orElse(0);
        assertTrue(hitAt5 >= 0.75, "HitRate@5 below floor: " + hitAt5);
    }

    private static String formatBenchmark(String name, String rerankerMode, Benchmark benchmark) {
        return name + " reranker=" + rerankerMode + " "
                + RetrievalMetrics.summarize(benchmark.answered())
                + String.format(" NoResultRecall=%d/%d NoResultPrecision=%s",
                benchmark.trueEmptyPredictions(),
                benchmark.noResultTotal(),
                precision(benchmark.trueEmptyPredictions(), benchmark.emptyPredictions()));
    }

    private Benchmark evaluate(ChunkReranker reranker) {
        List<RetrievalMetrics.QueryResult> answered = new ArrayList<>();
        int noResultTotal = 0;
        int emptyPredictions = 0;
        int trueEmptyPredictions = 0;
        for (GoldQuery goldQuery : goldSet.queries) {
            List<RetrievedChunk> results = hybridRetrievalService.search(goldQuery.query, 5, reranker);
            if (results.isEmpty()) {
                emptyPredictions++;
            }
            if (goldQuery.no_result) {
                noResultTotal++;
                if (results.isEmpty()) {
                    trueEmptyPredictions++;
                }
                continue;
            }
            Set<String> relevant = goldQuery.relevant.stream()
                    .map(item -> item.file_name + "|" + item.title_contains)
                    .collect(Collectors.toSet());
            List<String> ranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                RetrievedChunk result = results.get(i);
                String label = relevantLabel(result, goldQuery.relevant);
                ranked.add(label == null ? "miss-" + goldQuery.id + "-" + i : label);
            }
            answered.add(new RetrievalMetrics.QueryResult(goldQuery.id, ranked, relevant));
        }
        return new Benchmark(answered, noResultTotal, emptyPredictions, trueEmptyPredictions);
    }

    private static String precision(int trueEmpty, int predictedEmpty) {
        if (predictedEmpty == 0) {
            return "n/a";
        }
        return String.format("%.3f", (double) trueEmpty / predictedEmpty);
    }

    private record Benchmark(
            List<RetrievalMetrics.QueryResult> answered,
            int noResultTotal,
            int emptyPredictions,
            int trueEmptyPredictions) {
    }

    private static String relevantLabel(RetrievedChunk result, List<GoldRelevant> relevantList) {
        for (GoldRelevant gold : relevantList) {
            if (!gold.file_name.equals(result.getFileName())) {
                continue;
            }
            String titleContains = gold.title_contains;
            if (titleContains == null || titleContains.isBlank()) {
                return gold.file_name + "|";
            }
            String title = result.getTitle();
            if (title != null && title.contains(titleContains)) {
                return gold.file_name + "|" + titleContains;
            }
        }
        return null;
    }

    static class GoldSet {
        public List<GoldQuery> queries;
    }

    static class GoldQuery {
        public String id;
        public String query;
        public List<GoldRelevant> relevant;
        public boolean no_result;
    }

    static class GoldRelevant {
        public String file_name;
        public String title_contains;
    }
}
