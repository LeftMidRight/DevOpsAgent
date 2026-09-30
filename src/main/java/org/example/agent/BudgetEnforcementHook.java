package org.example.agent;

import org.example.context.TokenEstimator;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 在每次模型调用前检查运行预算：模型调用次数、总耗时、本轮消息 token 与终止状态。
 */
public class BudgetEnforcementHook {

    private final AgentRunContext runContext;
    private final TokenEstimator tokenEstimator;
    private final int messageTokenBudget;

    public BudgetEnforcementHook(AgentRunContext runContext) {
        this(runContext, new TokenEstimator(), Integer.MAX_VALUE);
    }

    public BudgetEnforcementHook(AgentRunContext runContext, TokenEstimator tokenEstimator, int messageTokenBudget) {
        this.runContext = runContext;
        this.tokenEstimator = tokenEstimator == null ? new TokenEstimator() : tokenEstimator;
        this.messageTokenBudget = messageTokenBudget;
    }

    public void beforeModel(List<Message> messages) {
        runContext.checkModelBudget();
        int used = 0;
        if (messages != null) {
            for (Message message : messages) {
                String text = message == null ? null : message.getText();
                if (text != null) {
                    used += tokenEstimator.estimate(text);
                }
            }
        }
        if (used > messageTokenBudget) {
            throw new BudgetExceededException(
                    "本轮消息约 " + used + " token，超过上下文预算 " + messageTokenBudget,
                    BudgetExceededException.Kind.CONTEXT_TOKENS);
        }
    }
}
