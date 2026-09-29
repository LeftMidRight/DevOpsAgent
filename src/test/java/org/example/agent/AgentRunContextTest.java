package org.example.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunContextTest {

    @Test
    void rejectLateResult_throwsWhenDeadlinePassed() {
        AgentProperties.Budget budget = new AgentProperties.Budget();
        AgentRunContext ctx = new AgentRunContext(
                UUID.randomUUID(), TaskMode.CHAT, budget, AgentExecutionEvents.NOOP,
                Instant.now().minusSeconds(1), new RunCancellation());

        BudgetExceededException ex = assertThrows(BudgetExceededException.class, ctx::rejectLateResult);
        assertFalse(ex.allowsPartialResult());
        assertTrue(ex.getMessage().contains("耗时") || ex.getMessage().contains("超时"));
    }

    @Test
    void rejectLateResult_throwsWhenCancelled() {
        RunCancellation cancellation = new RunCancellation();
        cancellation.cancel();
        AgentRunContext ctx = new AgentRunContext(
                UUID.randomUUID(), TaskMode.CHAT, new AgentProperties.Budget(),
                AgentExecutionEvents.NOOP, Instant.now().plusSeconds(30), cancellation);

        BudgetExceededException ex = assertThrows(BudgetExceededException.class, ctx::rejectLateResult);
        assertFalse(ex.allowsPartialResult());
    }

    @Test
    void addEvidences_assignsDistinctIdsForAlertItems() {
        AgentRunContext ctx = newContext();
        var items = ctx.addEvidences("queryPrometheusAlerts", "local", "{}",
                "{\"success\":true,\"alerts\":[{\"alert_name\":\"A\",\"service\":\"s1\"},{\"alert_name\":\"B\",\"service\":\"s2\"}]}");
        assertEquals(2, items.size());
        assertEquals("ev-1", items.get(0).id());
        assertEquals("ev-2", items.get(1).id());
        assertEquals("s1", items.get(0).service());
        assertEquals("s2", items.get(1).service());
    }

    private static AgentRunContext newContext() {
        return new AgentRunContext(
                UUID.randomUUID(), TaskMode.CHAT, new AgentProperties.Budget(),
                AgentExecutionEvents.NOOP, Instant.now().plusSeconds(30), new RunCancellation());
    }
}
