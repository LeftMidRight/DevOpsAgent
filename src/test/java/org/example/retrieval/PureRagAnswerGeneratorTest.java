package org.example.retrieval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureRagAnswerGeneratorTest {

    @Test
    void promptNumbersContextsInRetrieverOrder() {
        String prompt = PureRagAnswerGenerator.userPrompt(
                "OOMKilled 怎么处理",
                List.of("第一名上下文", "第二名上下文"));

        assertTrue(prompt.contains("Question:\nOOMKilled 怎么处理"));
        assertTrue(prompt.indexOf("[1] 第一名上下文") < prompt.indexOf("[2] 第二名上下文"));
        assertTrue(PureRagAnswerGenerator.SYSTEM_PROMPT.contains("不要调用任何工具"));
        assertTrue(PureRagAnswerGenerator.SYSTEM_PROMPT.contains("无法从知识库回答"));
    }

    @Test
    void emptyContextAsksModelToAbstain() {
        String prompt = PureRagAnswerGenerator.userPrompt("火星基地氧气阀门检修规程", List.of());

        assertTrue(prompt.contains("Context:\n（无）"));
        assertFalse(prompt.contains("[1]"));
    }

    @Test
    void requestHasNoToolsAndDisablesThinking() throws Exception {
        JsonMapper mapper = new JsonMapper();
        var bodies = new CopyOnWriteArrayList<String>();
        var paths = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            paths.add(exchange.getRequestURI().getPath());
            byte[] response = """
                    {"id":"call-1","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"无法从知识库回答"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        try {
            ObservationRegistry observations = ObservationRegistry.create();
            ChatModel model = OpenAiChatModel.builder()
                    .observationRegistry(observations)
                    .meterRegistry(new SimpleMeterRegistry())
                    .options(OpenAiChatOptions.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/plan/v3")
                            .apiKey("test-key")
                            .timeout(Duration.ofSeconds(5))
                            .model("DeepSeek-V4.1-Flash")
                            .build())
                    .build();
            String answer = new PureRagAnswerGenerator(model, "DeepSeek-V4.1-Flash")
                    .generate("OOMKilled 怎么处理", List.of());
            assertTrue(answer.contains("无法从知识库回答"));
            assertEqualsPath(paths);
            JsonNode body = mapper.readTree(bodies.get(0));
            assertTrue(body.path("model").asText().equals("DeepSeek-V4.1-Flash"), bodies.get(0));
            assertTrue(body.path("tools").isMissingNode() || body.path("tools").isEmpty());
            assertTrue("disabled".equals(body.at("/thinking/type").asText()));
        } finally {
            server.stop(0);
        }
    }

    private static void assertEqualsPath(List<String> paths) {
        assertTrue(paths.stream().allMatch(path -> path.equals("/api/plan/v3/chat/completions")), paths.toString());
    }
}
