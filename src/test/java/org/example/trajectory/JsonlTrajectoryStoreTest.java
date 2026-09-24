package org.example.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.context.ContextPackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlTrajectoryStoreTest {

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private JsonlTrajectoryStore store;
    private TrajectoryService trajectoryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        TrajectoryProperties properties = new TrajectoryProperties();
        properties.setBaseDir(tempDir.toString());
        properties.setMaxContentChars(10_000);
        store = new JsonlTrajectoryStore(objectMapper, properties);
        trajectoryService = new TrajectoryService(store, new TrajectorySanitizer(objectMapper, properties));
    }

    @Test
    void appendsOneValidJsonObjectPerLineWithMonotonicSequence() throws Exception {
        String conversationId = "../../unsafe/session";
        TrajectoryRun run = trajectoryService.startRun(
                conversationId, UUID.randomUUID(), "CHAT", "sync", "当前问题");
        run.recordContext(new ContextPackage(List.of(), 120, 4, 8));
        run.complete("最终回答");

        Path path = store.pathForConversation(conversationId);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        assertTrue(path.normalize().startsWith(tempDir.toAbsolutePath().normalize()));
        assertFalse(path.getFileName().toString().contains("unsafe"));
        assertEquals(4, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            JsonNode event = objectMapper.readTree(lines.get(i));
            assertEquals(i + 1, event.get("sequence").asLong());
            assertEquals(run.runId().toString(), event.get("runId").asText());
            assertEquals(conversationId, event.get("conversationId").asText());
        }
        assertEquals("run.completed", objectMapper.readTree(lines.get(3)).get("type").asText());
    }

    @Test
    void capturesNewAgentMessagesWithoutDuplicatingInputContextAndRedactsSecrets() throws Exception {
        UUID requestId = UUID.randomUUID();
        TrajectoryRun run = trajectoryService.startRun("session-1", requestId, "CHAT", "stream", "查一下状态");
        List<Message> initial = List.of(
                new UserMessage("历史问题"),
                new AssistantMessage("历史回答"),
                new UserMessage("查一下状态"));
        TrajectoryModelHook hook = run.captureHook(initial);

        hook.beforeModel(initial, null);
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "queryStatus", "{\"api_key\":\"top-secret\",\"id\":42}")))
                .build();
        hook.afterModel(append(initial, toolCall), null);

        ToolResponseMessage toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "queryStatus", "{\"password\":\"hidden\",\"status\":\"ok\"}")))
                .build();
        hook.beforeModel(append(initial, toolCall, toolResult), null);
        run.fail(new IllegalStateException("downstream failed"));
        run.complete("must not create a second terminal event");

        List<JsonNode> events = Files.readAllLines(store.pathForConversation("session-1"))
                .stream()
                .map(this::readTree)
                .toList();

        assertEquals(1, events.stream().filter(event -> "message.user".equals(type(event))).count());
        JsonNode assistant = events.stream()
                .filter(event -> "message.assistant".equals(type(event)))
                .findFirst()
                .orElseThrow();
        assertEquals("[REDACTED]",
                assistant.at("/payload/toolCalls/0/arguments/api_key").asText());

        JsonNode result = events.stream()
                .filter(event -> "tool.result".equals(type(event)))
                .findFirst()
                .orElseThrow();
        assertEquals("[REDACTED]", result.at("/payload/result/password").asText());
        assertEquals("ok", result.at("/payload/result/status").asText());
        assertEquals(1, events.stream().filter(event -> type(event).startsWith("run.fail")).count());
        assertEquals(0, events.stream().filter(event -> "run.completed".equals(type(event))).count());
    }

    private List<Message> append(List<Message> original, Message... messages) {
        java.util.ArrayList<Message> combined = new java.util.ArrayList<>(original);
        combined.addAll(List.of(messages));
        return List.copyOf(combined);
    }

    private JsonNode readTree(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("invalid JSONL line", e);
        }
    }

    private String type(JsonNode event) {
        return event.get("type").asText();
    }
}
