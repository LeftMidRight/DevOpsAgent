package org.example.retrieval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureRagGoldDatasetTest {

    private final JsonMapper jsonMapper = new JsonMapper();

    @Test
    void goldDatasetCoversEveryRetrievalQuery() throws Exception {
        JsonNode gold = jsonMapper.readTree(resource("/retrieval/gold-set.json"));
        List<JsonNode> samples = readJsonl("/retrieval/ragas-dataset.jsonl");

        assertEquals(gold.get("queries").size(), samples.size());
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < samples.size(); i++) {
            JsonNode sample = samples.get(i);
            JsonNode query = gold.get("queries").get(i);
            assertEquals(query.get("id").asText(), sample.get("id").asText());
            assertEquals(query.get("query").asText(), sample.get("user_input").asText());
            assertEquals(query.path("no_result").asBoolean(false), sample.get("no_result").asBoolean());
            assertFalse(sample.get("reference").asText().isBlank());
            ids.add(sample.get("id").asText());

            JsonNode contexts = sample.get("reference_contexts");
            JsonNode contextIds = sample.get("reference_context_ids");
            assertEquals(contexts.size(), contextIds.size());
            if (sample.get("no_result").asBoolean()) {
                assertEquals(0, contexts.size());
                continue;
            }
            assertFalse(contexts.isEmpty(), sample.get("id").asText());
            for (int j = 0; j < contextIds.size(); j++) {
                String contextId = contextIds.get(j).asText();
                assertTrue(contextId.contains("#"), contextId);
                String section = contextId.substring(contextId.indexOf('#') + 1);
                assertTrue(contexts.get(j).asText().contains(section), contextId);
            }
        }
        assertEquals(samples.size(), ids.size());
    }

    private List<JsonNode> readJsonl(String name) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(jsonMapper.readTree(line));
                }
            }
        }
        return rows;
    }

    private InputStream resource(String name) {
        InputStream in = getClass().getResourceAsStream(name);
        assertTrue(in != null, name);
        return in;
    }
}
