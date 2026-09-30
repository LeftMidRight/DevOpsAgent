package org.example.trajectory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;

/**
 * 记录本轮新产生的模型消息和工具结果，不重复写入组装好的历史上下文。
 */
public final class TrajectoryModelHook {

    private final TrajectoryRun run;
    private final List<Message> initialMessages;
    private int cursor;
    private boolean initialized;

    TrajectoryModelHook(TrajectoryRun run, List<Message> initialMessages) {
        this.run = run;
        this.initialMessages = List.copyOf(initialMessages);
    }

    public synchronized void beforeModel(List<Message> messages) {
        capture(messages, "before_model", true);
    }

    public synchronized void afterModel(List<Message> messages) {
        capture(messages, "after_model", false);
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
        return messages.size();
    }
}
