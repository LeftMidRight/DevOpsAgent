package org.example.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.repository.ChunkRepository;
import org.example.service.DocumentChunkingService;
import org.example.service.DocumentUploadService;
import org.example.service.HybridRetrievalService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridRetrievalEvalIT {

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private DocumentUploadService documentUploadService;

    @Autowired
    private DocumentChunkingService documentChunkingService;

    @Autowired
    private ChunkRepository chunkRepository;

    private GoldSet goldSet;

    @BeforeAll
    void setUp() {
        if (chunkRepository.findAll().isEmpty()) {
            uploadAiopDocs();
        }
        try (InputStream in = getClass().getResourceAsStream("/retrieval/gold-set.json")) {
            goldSet = new ObjectMapper().readValue(in, GoldSet.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load gold-set.json", e);
        }
    }

    private void uploadAiopDocs() {
        Path docsDir = Paths.get("aiops-docs");
        try (Stream<Path> files = Files.list(docsDir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(this::upload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload aiops-docs", e);
        }
    }

    private void upload(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            var summary = documentUploadService.uploadDocument(
                    file.getFileName().toString(), Files.size(file), in);
            documentChunkingService.chunkDocument(UUID.fromString(summary.id()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload " + file, e);
        }
    }

    @Test
    void recallAt5() {
        int hits = 0;
        int total = goldSet.queries.size();

        for (GoldQuery goldQuery : goldSet.queries) {
            List<RetrievedChunk> results = hybridRetrievalService.search(goldQuery.query, 5);
            boolean hit = results.stream().anyMatch(r -> isRelevant(r, goldQuery.relevant));
            if (hit) {
                hits++;
            }
            System.out.printf("%s %s%n", goldQuery.id, hit ? "HIT" : "MISS");
        }

        double recall = total == 0 ? 0.0 : (double) hits / total;
        System.out.printf("Recall@5 = %.2f%%%n", recall * 100);
        assertTrue(recall >= 0.75, "Recall@5 below floor: " + recall);
    }

    private static boolean isRelevant(RetrievedChunk result, List<GoldRelevant> relevantList) {
        for (GoldRelevant gold : relevantList) {
            if (!gold.file_name.equals(result.getFileName())) {
                continue;
            }
            String titleContains = gold.title_contains;
            if (titleContains == null || titleContains.isBlank()) {
                return true;
            }
            String title = result.getTitle();
            if (title == null) {
                continue;
            }
            if (title.contains(titleContains)) {
                return true;
            }
        }
        return false;
    }

    static class GoldSet {
        public List<GoldQuery> queries;
    }

    static class GoldQuery {
        public String id;
        public String query;
        public List<GoldRelevant> relevant;
    }

    static class GoldRelevant {
        public String file_name;
        public String title_contains;
    }
}
