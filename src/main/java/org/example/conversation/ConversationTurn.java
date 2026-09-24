package org.example.conversation;

import java.util.UUID;

public record ConversationTurn(String conversationId, UUID requestId, UUID userMessageId) {
}
