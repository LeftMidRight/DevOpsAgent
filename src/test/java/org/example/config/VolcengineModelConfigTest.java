package org.example.config;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.example.agent.AgentProperties;
import org.example.agent.AgentTaskPolicy;
import org.example.agent.ReasoningContentExtractor;
import org.example.agent.UnifiedAgentFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VolcengineModelConfigTest {

    @Test
    void sdkPreservesDoubaoPathThinkingReasoningStreamUsageAndMetrics() throws Exception {
        JsonMapper mapper = new JsonMapper();
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var paths = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            paths.add(exchange.getRequestURI().getPath());
            String body;
            if (request.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                body = """
                        data: {"id":"stream-1","object":"chat.completion.chunk","created":1,"model":"doubao-test","choices":[{"index":0,"delta":{"role":"assistant","content":"FI","reasoning_content":"think "}}]}

                        data: {"id":"stream-1","object":"chat.completion.chunk","created":1,"model":"doubao-test","choices":[{"index":0,"delta":{"content":"NAL","reasoning_content":"done"},"finish_reason":"stop"}]}

                        data: {"id":"stream-1","object":"chat.completion.chunk","created":1,"model":"doubao-test","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

                        data: [DONE]

                        """;
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                body = """
                        {"id":"call-1","object":"chat.completion","created":1,"model":"doubao-test","choices":[{"index":0,"message":{"role":"assistant","content":"FINAL","reasoning_content":"think done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                        """;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            observations.observationConfig().observationHandler(
                    new org.springframework.ai.chat.observation.ChatModelMeterObservationHandler(meters));
            VolcengineProperties connection = new VolcengineProperties();
            connection.setApiKey("offline-test-key");
            connection.getChat().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/coding/v3");
            AgentProperties props = new AgentProperties();
            props.setModel("doubao-test");
            UnifiedAgentFactory factory = new UnifiedAgentFactory(props, connection, null, null, null, null, null, observations, meters);
            ChatModel model = factory.createChatModel(AgentTaskPolicy.of(props));
            ChatClient client = ChatClient.builder(model, observations, null, null).build();

            ChatResponse sync = client.prompt(new Prompt("hello")).call().chatResponse();
            assertEquals("FINAL", sync.getResult().getOutput().getText());
            assertEquals("think done", ReasoningContentExtractor.extract(sync.getResult().getOutput()));
            assertEquals(15, sync.getMetadata().getUsage().getTotalTokens());

            AtomicReference<ChatResponse> streamed = new AtomicReference<>();
            StringBuilder reasoning = new StringBuilder();
            new MessageAggregator().aggregate(client.prompt(new Prompt("hello")).stream().chatResponse().doOnNext(part -> {
                if (part.getResult() != null) {
                    String delta = ReasoningContentExtractor.extract(part.getResult().getOutput());
                    if (delta != null) {
                        assertTrue(delta.startsWith(reasoning.toString()));
                        reasoning.setLength(0);
                        reasoning.append(delta);
                    }
                }
            }), streamed::set).blockLast(Duration.ofSeconds(10));
            assertEquals("FINAL", streamed.get().getResult().getOutput().getText());
            assertEquals("think done", reasoning.toString());
            assertEquals(15, streamed.get().getMetadata().getUsage().getTotalTokens());
            assertEquals(2, paths.size());
            assertTrue(paths.stream().allMatch(path -> path.equals("/api/coding/v3/chat/completions")));
            assertEquals("enabled", requests.get(0).at("/thinking/type").asText());
            assertTrue(requests.get(1).at("/stream_options/include_usage").asBoolean());
            assertFalse(meters.find("gen_ai.client.token.usage").meters().isEmpty());
        } finally {
            meters.close();
            server.stop(0);
        }
    }
}
