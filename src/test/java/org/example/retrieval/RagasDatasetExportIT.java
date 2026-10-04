package org.example.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.example.agent.AgentProperties;
import org.example.config.MinioProperties;
import org.example.config.RetrievalProperties;
import org.example.config.VolcengineProperties;
import org.example.dto.ChatAnswer;
import org.example.dto.ChatRequest;
import org.example.service.ChatApplicationService;
import org.example.service.DocumentIngestionService;
import org.example.service.DocumentUploadService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ragas 数据集导出器：跑真实 Agent，采集每 case 实际 queryInternalDocs
 * 的检索上下文与最终回答，导出 Ragas 输入 JSONL。
 *
 * 仅在 rag-eval profile 且显式 -Dragas.export=true 时运行；
 * 普通 mvn test / mvn verify 不产生 LLM 成本。
 *
 * 输出：eval/ragas/input/ragas-eval-dataset.jsonl（+ .meta.json 运行元数据）
 */
@SpringBootTest
@ActiveProfiles("rag-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "ragas.export", matches = "true")
class RagasDatasetExportIT {

    private static final Path OUTPUT_DIR = Paths.get("eval", "ragas", "input");
    private static final Path OUTPUT_FILE = OUTPUT_DIR.resolve("ragas-eval-dataset.jsonl");
    private static final Path META_FILE = OUTPUT_DIR.resolve("ragas-eval-dataset.meta.json");

    @Autowired
    private ChatApplicationService chatApplicationService;

    @Autowired
    private RagEvalInternalDocsCollector collector;

    @Autowired
    private DocumentUploadService documentUploadService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired
    private AgentProperties agentProperties;

    @Autowired
    private VolcengineProperties volcengineProperties;

    @Autowired
    private MinioProperties minioProperties;

    @Autowired
    private MinioClient minioClient;

    @Value("${retrieval.reranker.type:none}")
    private String rerankerType;

    @Value("${rag.top-k:3}")
    private int ragTopK;

    private final JsonMapper jsonMapper = new JsonMapper();

    private GoldSet goldSet;
    private Map<String, String> referencesById;
    private long publicDocumentsBefore;
    private int devBucketObjectsBefore;

    @BeforeAll
    void setUp() throws IOException {
        if (!HybridRetrievalEvalIT.embeddingConfigured()) {
            fail("RagasDatasetExportIT 需要配置 VOLCENGINE_API_KEY 或 ARK_API_KEY");
        }
        assertEvalIsolation();
        publicDocumentsBefore = countTable("public", "knowledge_documents");
        devBucketObjectsBefore = countBucketObjects("superbiz-documents");
        resetEvalSchema();
        uploadAiopsDocs();
        assertEvalDataLandedApartFromDev();
        goldSet = loadResource("/retrieval/gold-set.json", GoldSet.class);
        referencesById = loadReferences();
        assertEquals(goldSet.queries.size(), referencesById.size(),
                "每条 gold query 都应有 reference，缺少: " + missingReferenceIds());
    }

    @Test
    void exportRagasDataset() throws IOException {
        collector.clear();
        Files.createDirectories(OUTPUT_DIR);

        Map<String, Object> runMeta = new LinkedHashMap<>();
        runMeta.put("generatedAt", Instant.now().toString());
        runMeta.put("caseCount", goldSet.queries.size());
        runMeta.put("reranker", rerankerType);
        runMeta.put("topK", ragTopK);
        runMeta.put("vectorTopN", retrievalProperties.getVectorTopN());
        runMeta.put("bm25TopN", retrievalProperties.getBm25TopN());
        runMeta.put("candidateTopN", retrievalProperties.getCandidateTopN());
        runMeta.put("rrfK", retrievalProperties.getRrfK());
        runMeta.put("agentModel", agentProperties.getModel());
        runMeta.put("embeddingModel", volcengineProperties.getEmbedding().getModel());

        List<String> failedCases = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        try (var writer = Files.newBufferedWriter(OUTPUT_FILE)) {
            for (GoldQuery gold : goldSet.queries) {
                int marker = collector.size();
                ChatAnswer answer = null;
                String error = null;
                try {
                    ChatRequest request = new ChatRequest();
                    request.setQuestion(gold.query);
                    answer = chatApplicationService.chat(request);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                List<RagEvalInternalDocsCollector.QueryCall> calls = collector.snapshot(marker);
                Map<String, Object> record = buildRecord(gold, answer, calls, error);
                writer.write(jsonMapper.writeValueAsString(record));
                writer.newLine();

                if (error != null) {
                    failedCases.add(gold.id);
                }
                log.append(String.format(
                        "[ragas-export] id=%s toolCalls=%d contexts=%d answerChars=%d error=%s%n",
                        gold.id, calls.size(),
                        ((List<?>) record.get("retrieved_contexts")).size(),
                        answer == null || answer.answer() == null ? 0 : answer.answer().length(),
                        error == null ? "none" : error));
            }
        }

        runMeta.put("failedCases", failedCases);
        runMeta.put("failedCount", failedCases.size());
        Files.writeString(META_FILE, jsonMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(runMeta));

        System.out.print(log);
        System.out.println("[ragas-export] dataset: " + OUTPUT_FILE.toAbsolutePath());
        System.out.println("[ragas-export] meta: " + META_FILE.toAbsolutePath());
        System.out.println("[ragas-export] failed=" + failedCases);

        assertTrue(Files.exists(OUTPUT_FILE), "JSONL 导出文件应存在");
        assertEquals(goldSet.queries.size(), Files.readAllLines(OUTPUT_FILE).size(),
                "每个 case 应导出一行");
        assertEquals(List.of(), failedCases, "存在失败 case: " + failedCases);
    }

    private Map<String, Object> buildRecord(
            GoldQuery gold,
            ChatAnswer answer,
            List<RagEvalInternalDocsCollector.QueryCall> calls,
            String error) {
        List<String> contexts = new ArrayList<>();
        List<String> contextIds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RagEvalInternalDocsCollector.QueryCall call : calls) {
            for (RetrievedChunk chunk : call.chunks()) {
                String id = chunk.getId() == null ? chunk.getContent() : chunk.getId();
                if (seen.add(id)) {
                    contextIds.add(chunk.getId());
                    contexts.add(chunk.getContent());
                }
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reranker", rerankerType);
        metadata.put("top_k", ragTopK);
        metadata.put("vector_top_n", retrievalProperties.getVectorTopN());
        metadata.put("bm25_top_n", retrievalProperties.getBm25TopN());
        metadata.put("candidate_top_n", retrievalProperties.getCandidateTopN());
        metadata.put("rrf_k", retrievalProperties.getRrfK());
        metadata.put("agent_model", agentProperties.getModel());
        metadata.put("embedding_model", volcengineProperties.getEmbedding().getModel());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", gold.id);
        record.put("user_input", gold.query);
        record.put("retrieved_contexts", contexts);
        record.put("retrieved_context_ids", contextIds);
        record.put("response", answer == null || answer.answer() == null ? "" : answer.answer());
        record.put("reference", referencesById.get(gold.id));
        record.put("no_result", gold.no_result);
        record.put("metadata", metadata);
        if (error != null) {
            record.put("error", error);
        }
        return record;
    }

    private Map<String, String> loadReferences() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/retrieval/ragas-reference.json")) {
            JsonNode root = jsonMapper.readTree(in);
            Map<String, String> map = new LinkedHashMap<>();
            for (JsonNode node : root.get("references")) {
                map.put(node.get("id").asText(), node.get("reference").asText());
            }
            return map;
        }
    }

    private List<String> missingReferenceIds() {
        List<String> missing = new ArrayList<>();
        for (GoldQuery query : goldSet.queries) {
            if (!referencesById.containsKey(query.id)) {
                missing.add(query.id);
            }
        }
        return missing;
    }

    private <T> T loadResource(String name, Class<T> type) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return jsonMapper.readValue(in, type);
        }
    }

    private void assertEvalIsolation() {
        assertEquals("superbiz-rag-eval", minioProperties.getBucket());
        String searchPath = jdbcTemplate.queryForObject("SHOW search_path", String.class);
        String normalizedPath = searchPath == null ? "" : searchPath.replace(" ", "").trim();
        assertTrue(normalizedPath.startsWith("rag_eval,public"),
                "search_path 必须优先 rag_eval 并保留 public，实际: " + searchPath);
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

    static class GoldSet {
        public List<GoldQuery> queries;
    }

    static class GoldQuery {
        public String id;
        public String query;
        public boolean no_result;
    }
}
