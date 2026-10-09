package org.example.conversation;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final RuntimeConversationStore runtimeConversationStore;

    public ConversationService(
            ConversationRepository conversationRepository,
            RuntimeConversationStore runtimeConversationStore) {
        this.conversationRepository = conversationRepository;
        this.runtimeConversationStore = runtimeConversationStore;
    }

    public String resolveConversationId(String requestedId) {
        String conversationId = requestedId == null || requestedId.isBlank()
                ? "session_" + UUID.randomUUID()
                : requestedId.trim();
        if (conversationId.length() > 128) {
            throw new IllegalArgumentException("会话ID长度不能超过128个字符");
        }
        return conversationId;
    }

    public void clear(String conversationId) {
        conversationRepository.clear(conversationId);
        runtimeConversationStore.remove(conversationId);
    }

    public Optional<ConversationState> find(String conversationId) {
        return conversationRepository.find(conversationId);
    }

    public int countCompletedTurns(String conversationId) {
        return conversationRepository.countCompletedTurns(conversationId);
    }
}
