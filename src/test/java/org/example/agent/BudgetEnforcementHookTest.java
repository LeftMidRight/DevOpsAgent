package org.example.agent;

import org.example.context.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BudgetEnforcementHookTest {

    @Test
    void beforeModel_rejectsWhenMessageTokensExceedBudget() {
        AgentRunContext ctx = new AgentRunContext(
                UUID.randomUUID(), TaskMode.CHAT, new AgentProperties.Budget(),
                AgentExecutionEvents.NOOP, Instant.now().plusSeconds(30), new RunCancellation());
        BudgetEnforcementHook hook = new BudgetEnforcementHook(ctx, new TokenEstimator(), 20);

        BudgetExceededException ex = assertThrows(BudgetExceededException.class,
                () -> hook.beforeModel(List.of(new UserMessage("中".repeat(80))), null));
        assertTrue(ex.allowsPartialResult());
        assertTrue(ex.getMessage().contains("上下文") || ex.getMessage().contains("token"));
    }
}
