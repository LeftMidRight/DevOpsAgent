package org.example.trajectory;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TrajectoryService {

    private final JsonlTrajectoryStore store;
    private final TrajectorySanitizer sanitizer;

    public TrajectoryService(JsonlTrajectoryStore store, TrajectorySanitizer sanitizer) {
        this.store = store;
        this.sanitizer = sanitizer;
    }

    public TrajectoryRun startRun(
            String conversationId,
            UUID requestId,
            String transport,
            String question) {
        TrajectoryRun run = new TrajectoryRun(
                UUID.randomUUID(), conversationId, requestId, "unified_business_agent", store, sanitizer);
        run.start(transport);
        run.recordUserMessage(question);
        return run;
    }
}
