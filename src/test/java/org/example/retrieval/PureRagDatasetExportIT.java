package org.example.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.example.agent.AgentProperties;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure RAG 数据集导出：每条 query 只检索一次，再用无工具的固定生成器回答。
 * 不经过 ChatApplicationService / Unified Agent。
 *
 * 仅在 -Dpure.rag.export=true 时运行，普通 mvn test 不产生生成成本。
 * 可用 -Dpure.rag.ids=cpu-001,mem-003 跑子集。
 */
@SpringBootTest
@ActiveProfiles("rag-eval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "pure.rag.export", matches = "true")
class PureRagDatasetExportIT {

    private static final Path OUTPUT_DIR = Paths.get("eval", "ragas", "input");
    private static final Path OUTPUT_FILE = OUTPUT_DIR.resolve("pure-rag-eval-dataset.jsonl");
    private static final Path META_FILE = OUTPUT_DIR.resolve("pure-rag-eval-dataset.meta.json");

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private ChatModel chatModel;

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
    private List<GoldSample> samples;
    private long publicDocumentsBefore;
    private int devBucketObjectsBefore;
    private String datasetHash;

    @BeforeAll
    void setUp() throws Exception {
        if (!HybridRetrievalEvalIT.embeddingConfigured()) {
            fail("PureRagDatasetExportIT 需要配置 VOLCENGINE_API_KEY 或 ARK_API_KEY");
        }
        assertEvalIsolation();
        publicDocumentsBefore = countTable("public", "knowledge_documents");
        devBucketObjectsBefore = countBucketObjects("superbiz-documents");
        resetEvalSchema();
        uploadAiopsDocs();
        assertEvalDataLandedApartFromDev();
        samples = loadSamples();
        assertFalseEmpty(samples);
    }

    @Test
    void exportPureRagDataset() throws Exception {
        List<GoldSample> selected = selectSamples();
        Files.createDirectories(OUTPUT_DIR);
        PureRagAnswerGenerator generator = new PureRagAnswerGenerator(chatModel, agentProperties.getModel());
        List<String> failedCases = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        try (var writer = Files.newBufferedWriter(OUTPUT_FILE)) {
            for (GoldSample sample : selected) {
                String error = null;
                List<RetrievedChunk> chunks = List.of();
                String response = "";
                try {
                    chunks = hybridRetrievalService.search(sample.userInput, ragTopK);
                    assertTrue(chunks.size() <= ragTopK, sample.id + " 返回条数超过 TopK");
                    response = generator.generate(sample.userInput, chunks);
                } catch (Exception e) {
                    error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    failedCases.add(sample.id);
                }
                Map<String, Object> record = buildRecord(sample, chunks, response, error);
                writer.write(jsonMapper.writeValueAsString(record));
                writer.newLine();
                log.append(String.format(
                        "[pure-rag-export] id=%s contexts=%d answerChars=%d error=%s%n",
                        sample.id,
                        chunks.size(),
                        response.length(),
                        error == null ? "none" : error));
            }
        }

        Map<String, Object> runMeta = runMeta(selected, failedCases);
        Files.writeString(META_FILE, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runMeta));
        System.out.print(log);
        System.out.println("[pure-rag-export] dataset: " + OUTPUT_FILE.toAbsolutePath());
        System.out.println("[pure-rag-export] failed=" + failedCases);

        assertEquals(selected.size(), Files.readAllLines(OUTPUT_FILE).size());
        assertEquals(List.of(), failedCases, "存在失败 case: " + failedCases);
    }

    private Map<String, Object> buildRecord(
            GoldSample sample,
            List<RetrievedChunk> chunks,
            String response,
            String error) {
        List<String> contexts = new ArrayList<>();
        List<String> contextIds = new ArrayList<>();
        List<String> sectionIds = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            contexts.add(chunk.getContent() == null ? "" : chunk.getContent());
            contextIds.add(chunk.getId());
            String title = chunk.getTitle() == null ? "" : chunk.getTitle();
            sectionIds.add(chunk.getFileName() + "#" + title);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", sample.id);
        record.put("user_input", sample.userInput);
        record.put("reference", sample.reference);
        record.put("reference_contexts", sample.referenceContexts);
        record.put("reference_context_ids", sample.referenceContextIds);
        record.put("retrieved_contexts", contexts);
        record.put("retrieved_context_ids", contextIds);
        record.put("retrieved_section_ids", sectionIds);
        record.put("response", response);
        record.put("no_result", sample.noResult);
        record.put("metadata", experimentMetadata());
        if (error != null) {
            record.put("error", error);
        }
        return record;
    }

    private Map<String, Object> experimentMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("top_k", ragTopK);
        metadata.put("vector_top_n", retrievalProperties.getVectorTopN());
        metadata.put("bm25_top_n", retrievalProperties.getBm25TopN());
        metadata.put("candidate_top_n", retrievalProperties.getCandidateTopN());
        metadata.put("rrf_k", retrievalProperties.getRrfK());
        metadata.put("reranker", rerankerType);
        metadata.put("chunk_target_tokens", chunkConfig.getTargetTokens());
        metadata.put("chunk_max_tokens", chunkConfig.getMaxTokens());
        metadata.put("chunk_overlap_tokens", chunkConfig.getOverlapTokens());
        metadata.put("embedding_model", volcengineProperties.getEmbedding().getModel());
        metadata.put("generator_model", agentProperties.getModel());
        metadata.put("generator_temperature", PureRagAnswerGenerator.TEMPERATURE);
        metadata.put("generator_thinking", "disabled");
        metadata.put("dataset_sha256", datasetHash);
        return metadata;
    }

    private Map<String, Object> runMeta(List<GoldSample> selected, List<String> failedCases) {
        Map<String, Object> runMeta = new LinkedHashMap<>();
        runMeta.put("generatedAt", Instant.now().toString());
        runMeta.put("mode", "pure-rag");
        runMeta.put("caseCount", selected.size());
        runMeta.put("failedCases", failedCases);
        runMeta.put("failedCount", failedCases.size());
        runMeta.putAll(experimentMetadata());
        return runMeta;
    }

    private List<GoldSample> selectSamples() {
        String raw = System.getProperty("pure.rag.ids", "");
        if (raw == null || raw.isBlank()) {
            return samples;
        }
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
        for (String id : raw.split(",")) {
            if (!id.isBlank()) {
                wanted.add(id.trim());
            }
        }
        List<GoldSample> selected = samples.stream()
                .filter(sample -> wanted.contains(sample.id))
                .toList();
        assertEquals(wanted.size(), selected.size(), "pure.rag.ids 中有未知 id: " + raw);
        return selected;
    }

    private List<GoldSample> loadSamples() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/retrieval/ragas-dataset.jsonl")) {
            bytes = in.readAllBytes();
        }
        datasetHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        List<GoldSample> loaded = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = jsonMapper.readTree(line);
                GoldSample sample = new GoldSample();
                sample.id = node.get("id").asText();
                sample.userInput = node.get("user_input").asText();
                sample.reference = node.get("reference").asText();
                sample.noResult = node.path("no_result").asBoolean(false);
                sample.referenceContexts = textList(node.get("reference_contexts"));
                sample.referenceContextIds = textList(node.get("reference_context_ids"));
                loaded.add(sample);
            }
        }
        return loaded;
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private void assertFalseEmpty(List<GoldSample> loaded) {
        assertTrue(loaded != null && !loaded.isEmpty(), "ragas-dataset.jsonl 为空");
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
        } catch (java.io.IOException e) {
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
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to upload " + file, e);
        }
    }

    static class GoldSample {
        String id;
        String userInput;
        String reference;
        List<String> referenceContexts;
        List<String> referenceContextIds;
        boolean noResult;
    }
}
