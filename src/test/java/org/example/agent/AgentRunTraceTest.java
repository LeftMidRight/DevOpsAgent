package org.example.agent;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.example.observability.langfuse.AgentRunObservation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunTraceTest {

    @Test
    void syncAndStreamingRunsExposeRunIdentityWithoutChangingTheAnswer() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        for (boolean streaming : List.of(false, true)) {
            stopped.clear();
            ChatModel model = mock(ChatModel.class);
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("FINAL"))));
            if (streaming) {
                when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response));
            } else {
                when(model.call(any(Prompt.class))).thenReturn(response);
            }
            AgentProperties properties = new AgentProperties();
            properties.setModel("trace-model");
            AgentExecutionResult result = AgentExecutionHarness.create(model, properties, registry).service.execute(
                    new AgentExecutionService.AgentExecutionRequest(
                            "sess-1", "hello", streaming, AgentExecutionEvents.NOOP));

            assertEquals("FINAL", result.answer());
            Observation.Context run = stopped.stream()
                    .filter(context -> AgentRunObservation.NAME.equals(context.getName()))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            assertEquals(result.runId().toString(), value(run, "agent.run_id"));
            assertEquals(result.requestId().toString(), value(run, "agent.request_id"));
            assertEquals("sess-1", value(run, "agent.conversation_id"));
            assertEquals("success", value(run, "agent.status"));
            assertEquals(Boolean.toString(streaming), value(run, "agent.streaming"));
            assertEquals("trace-model", value(run, "agent.model"));
        }
    }

    private static String value(Observation.Context context, String key) {
        KeyValue low = context.getLowCardinalityKeyValue(key);
        if (low != null) {
            return low.getValue();
        }
        return context.getHighCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals(key))
                .map(KeyValue::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + key));
    }
}
