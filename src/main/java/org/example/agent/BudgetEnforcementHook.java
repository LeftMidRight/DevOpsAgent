package org.example.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.example.context.TokenEstimator;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 在每次模型调用前执行运行预算检查：模型调用次数、总耗时、本轮消息 token 与终止状态。
 * 超限时抛出 BudgetExceededException 中断 ReAct 循环。
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class BudgetEnforcementHook extends MessagesModelHook {

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

    @Override
    public String getName() {
        return "budget_enforcement";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
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
        return super.beforeModel(messages, config);
    }
}
