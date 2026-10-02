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
    }

    @Test
    void parse_throwsOnUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> TaskMode.parse("UNKNOWN"));
        assertThrows(IllegalArgumentException.class, () -> TaskMode.parse("OPS"));
    }

    @Test
    void agentTaskPolicy_providesAppropriatePolicy() {
        AgentProperties props = new AgentProperties();
        AgentTaskPolicy chatPolicy = AgentTaskPolicy.of(TaskMode.CHAT, props);
        assertNotNull(chatPolicy.systemPrompt());
        assertEquals(0.7, chatPolicy.temperature());
        assertEquals(2000, chatPolicy.maxTokens());
    }
}
