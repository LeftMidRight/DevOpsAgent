package org.example.service;

import org.example.conversation.ConversationExecutionCoordinator;
import org.example.conversation.ConversationService;
import org.example.conversation.ConversationState;
import org.example.dto.ClearRequest;
import org.example.dto.SessionInfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 会话管理应用入口（清空、查询），与 Agent 执行链路分离。
 */
@Service
public class SessionApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(SessionApplicationService.class);

    private final ConversationService conversationService;
    private final ConversationExecutionCoordinator conversationCoordinator;

    public SessionApplicationService(
            ConversationService conversationService,
            ConversationExecutionCoordinator conversationCoordinator) {
        this.conversationService = conversationService;
        this.conversationCoordinator = conversationCoordinator;
    }

    public void clearHistory(ClearRequest request) throws InterruptedException {
        if (request == null || request.getId() == null || request.getId().isEmpty()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());
        clearHistory(request.getId());
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
