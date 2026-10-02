package org.example.conversation;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;

    public ConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    public String resolveConversationId(String requestedId) {
        String conversationId = requestedId == null || requestedId.isBlank()
                ? "session_" + UUID.randomUUID()
                : requestedId.trim();
        if (conversationId.length() > 128) {
            throw new IllegalArgumentException("会话ID长度不能超过128个字符");
        }
        conversationRepository.createIfAbsent(conversationId);
        return conversationId;
    }

    public ConversationTurn beginTurn(String conversationId, String question) {
        return beginTurn(conversationId, question, null);
    }

    /** 任务模式写入消息 metadata，供后续按模式检索与审计 */
    public ConversationTurn beginTurn(String conversationId, String question, String taskMode) {
        UUID requestId = UUID.randomUUID();
        ConversationMessage userMessage = conversationRepository.appendMessage(
                conversationId, requestId, "user", question, "PENDING", modeMetadata(taskMode));
        return new ConversationTurn(conversationId, requestId, userMessage.id());
    }

    public void completeTurn(ConversationTurn turn, String answer) {
        conversationRepository.completeTurn(turn, answer);
    }

    public void completeTurn(ConversationTurn turn, String answer, String taskMode) {
        conversationRepository.completeTurn(turn, answer, modeMetadata(taskMode));
    }

    private static String modeMetadata(String taskMode) {
        if (taskMode == null || taskMode.isBlank()) {
            return null;
        }
        return "{\"mode\":\"" + taskMode + "\"}";
    }

    public void failTurn(ConversationTurn turn) {
        conversationRepository.failTurn(turn);
    }

    public void clear(String conversationId) {
        conversationRepository.clear(conversationId);
    }

    public Optional<ConversationState> find(String conversationId) {
        return conversationRepository.find(conversationId);
    }

    public int countCompletedTurns(String conversationId) {
        return conversationRepository.countCompletedTurns(conversationId);
    }
}
