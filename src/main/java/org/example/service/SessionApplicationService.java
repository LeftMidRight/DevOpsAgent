package org.example.service;

import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationService;
import org.example.conversation.ConversationState;
import org.example.dto.SessionInfoResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 会话管理应用入口（清空、查询），与 Agent 执行链路分离。
 */
@Service
public class SessionApplicationService {

    private final ConversationService conversationService;
    private final ConversationExecutionCoordinator conversationCoordinator;

    public SessionApplicationService(
            ConversationService conversationService,
            ConversationExecutionCoordinator conversationCoordinator) {
        this.conversationService = conversationService;
        this.conversationCoordinator = conversationCoordinator;
    }

    public void clearHistory(String sessionId) throws InterruptedException {
        try (ConversationExecutionCoordinator.Lease ignored = conversationCoordinator.acquire(sessionId)) {
            Optional<ConversationState> session = conversationService.find(sessionId);
            if (session.isEmpty()) {
                throw new IllegalArgumentException("会话不存在");
            }
            conversationService.clear(sessionId);
        }
    }

    public SessionInfoResponse getSessionInfo(String sessionId) {
        Optional<ConversationState> session = conversationService.find(sessionId);
        if (session.isEmpty()) {
            throw new IllegalArgumentException("会话不存在");
        }
        ConversationState state = session.get();
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(sessionId);
        response.setMessagePairCount(conversationService.countCompletedTurns(sessionId));
        response.setCreateTime(state.createdAt().toEpochMilli());
        return response;
    }
}
