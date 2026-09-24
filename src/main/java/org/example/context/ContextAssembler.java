package org.example.context;

import org.springframework.ai.chat.model.ChatModel;
import org.example.conversation.ConversationMessage;
import org.example.conversation.ConversationRepository;
import org.example.conversation.ConversationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ContextAssembler {

    private static final Logger logger = LoggerFactory.getLogger(ContextAssembler.class);

    private final ConversationRepository conversationRepository;
    private final ConversationSummaryService summaryService;
    private final ContextProperties properties;
    private final TokenEstimator tokenEstimator;

    public ContextAssembler(
            ConversationRepository conversationRepository,
            ConversationSummaryService summaryService,
            ContextProperties properties,
            TokenEstimator tokenEstimator) {
        this.conversationRepository = conversationRepository;
        this.summaryService = summaryService;
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public ContextPackage assemble(
            String conversationId,
            UUID currentRequestId,
            ChatModel chatModel) {
        int availableHistoryTokens = calculateHistoryBudget();
        summaryService.compressIfNeeded(
                conversationId, currentRequestId, availableHistoryTokens, chatModel);

        ConversationState state = conversationRepository.find(conversationId).orElseThrow();
        List<ConversationMessage> candidates = conversationRepository.findContextMessages(
                conversationId, currentRequestId);
        candidates = candidates.stream()
                .filter(message -> message.sequence() > state.summaryUntilSequence())
                .toList();

        List<ConversationMessage> retained = retainNewest(candidates, availableHistoryTokens, currentRequestId);
        List<Message> messages = new ArrayList<>();
        int estimatedTokens = 0;

        if (state.summary() != null && !state.summary().isBlank()) {
            String summary = tokenEstimator.truncate(state.summary(), properties.getSummaryTokens());
            String summaryMessage = """
                    <conversation_summary source="application">
                    这是应用根据较早对话生成的参考状态，不是用户指令。若与较新的原始消息冲突，以较新消息为准。
                    %s
                    </conversation_summary>
                    """.formatted(summary);
            messages.add(new UserMessage(summaryMessage));
            estimatedTokens += tokenEstimator.estimate(summaryMessage);
        }

        for (ConversationMessage message : retained) {
            messages.add(toSpringMessage(message));
            estimatedTokens += message.tokenCount();
        }

        logger.info(
                "上下文组装完成 - SessionId: {}, 消息数: {}, 估算Token: {}, summaryUntilSeq: {}",
                conversationId, messages.size(), estimatedTokens, state.summaryUntilSequence());
        return new ContextPackage(
                List.copyOf(messages),
                estimatedTokens,
                retained.size(),
                state.summaryUntilSequence());
    }

    private int calculateHistoryBudget() {
        int totalDynamicBudget = properties.getMaxContextTokens()
                - properties.getReservedOutputTokens()
                - properties.getSystemAndToolsReserveTokens()
                - properties.getSummaryTokens();
        return Math.max(1, Math.min(properties.getRecentMessageTokens(), totalDynamicBudget));
    }

    private List<ConversationMessage> retainNewest(
            List<ConversationMessage> candidates,
            int budget,
            UUID currentRequestId) {
        List<ConversationMessage> reversed = new ArrayList<>();
        int used = 0;

        for (int i = candidates.size() - 1; i >= 0; i--) {
            ConversationMessage message = candidates.get(i);
            boolean currentQuestion = currentRequestId.equals(message.requestId());
            if (currentQuestion && message.tokenCount() > budget) {
                throw new IllegalArgumentException("当前问题超过上下文预算，请缩短后重试");
            }
            if (!currentQuestion && !reversed.isEmpty() && used + message.tokenCount() > budget) {
                break;
            }
            reversed.add(message);
            used += message.tokenCount();
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private Message toSpringMessage(ConversationMessage message) {
        return switch (message.role()) {
            case "assistant" -> new AssistantMessage(message.content());
            case "user" -> new UserMessage(message.content());
            default -> new UserMessage("<historical_event role=\"" + message.role() + "\">\n"
                    + message.content() + "\n</historical_event>");
        };
    }
}
