package org.example.trajectory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;

@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public final class TrajectoryModelHook extends MessagesModelHook {

    private final TrajectoryRun run;
    private final List<Message> initialMessages;
    private int cursor;
    private boolean initialized;

    TrajectoryModelHook(TrajectoryRun run, List<Message> initialMessages) {
        this.run = run;
        this.initialMessages = List.copyOf(initialMessages);
    }

    @Override
    public String getName() {
        return "trajectory_capture";
    }

    @Override
    public synchronized AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        capture(messages, "before_model", true);
        return super.beforeModel(messages, config);
    }

    @Override
    public synchronized AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        capture(messages, "after_model", false);
        return super.afterModel(messages, config);
    }

    private void capture(List<Message> messages, String phase, boolean initializationOnly) {
        if (!initialized) {
            cursor = findEndOfInitialContext(messages);
            initialized = true;
            if (initializationOnly) {
                return;
            }
        }
        if (messages.size() < cursor) {
            cursor = messages.size();
            return;
        }
        while (cursor < messages.size()) {
            run.recordMessage(messages.get(cursor), phase);
            cursor++;
        }
    }

    private int findEndOfInitialContext(List<Message> messages) {
        if (initialMessages.isEmpty()) {
            return 0;
        }
        int lastStart = messages.size() - initialMessages.size();
        for (int start = 0; start <= lastStart; start++) {
            boolean match = true;
            for (int i = 0; i < initialMessages.size(); i++) {
                if (!Objects.equals(messages.get(start + i), initialMessages.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return start + initialMessages.size();
            }
        }
        // beforeModel runs before the first generated message. If a framework
        // version rewrites the input list, treating the current size as the
        // initial boundary prevents historical context from being duplicated.
        return messages.size();
    }
}
