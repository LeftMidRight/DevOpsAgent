package org.example.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskModeTest {

    @Test
    void parse_defaultsToChatWhenNullOrBlank() {
        assertEquals(TaskMode.CHAT, TaskMode.parse(null));
        assertEquals(TaskMode.CHAT, TaskMode.parse(""));
        assertEquals(TaskMode.CHAT, TaskMode.parse("   "));
    }

    @Test
    void parse_normalizesCase() {
        assertEquals(TaskMode.CHAT, TaskMode.parse("chat"));
        assertEquals(TaskMode.CHAT, TaskMode.parse("CHAT"));
        assertEquals(TaskMode.CHAT, TaskMode.parse("Chat"));
        assertEquals(TaskMode.OPS, TaskMode.parse("ops"));
        assertEquals(TaskMode.OPS, TaskMode.parse("OPS"));
        assertEquals(TaskMode.OPS, TaskMode.parse("Ops"));
    }

    @Test
    void parse_throwsOnUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> TaskMode.parse("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> TaskMode.parse("ADMIN"));
    }

    @Test
    void agentTaskPolicy_providesAppropriatePolicy() {
        AgentProperties props = new AgentProperties();
        AgentTaskPolicy chatPolicy = AgentTaskPolicy.of(TaskMode.CHAT, props);
        assertNotNull(chatPolicy.systemPrompt());
        assertEquals(0.7, chatPolicy.temperature());
        assertEquals(2000, chatPolicy.maxTokens());

        AgentTaskPolicy opsPolicy = AgentTaskPolicy.of(TaskMode.OPS, props);
        assertNotNull(opsPolicy.systemPrompt());
        assertTrue(opsPolicy.systemPrompt().contains("JSON"));
        assertEquals(0.3, opsPolicy.temperature());
        assertEquals(8000, opsPolicy.maxTokens());
    }
}
