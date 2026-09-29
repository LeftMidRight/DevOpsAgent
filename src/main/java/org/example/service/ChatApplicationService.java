package org.example.service;

import org.example.agent.AgentExecutionEvents;
import org.example.agent.AgentExecutionResult;
import org.example.agent.AgentExecutionService;
import org.example.agent.TaskMode;
import org.example.dto.ChatAnswer;
import org.example.dto.ChatRequest;
import org.example.support.ChatRequestSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 聊天应用入口：校验并归一化请求后委托统一 Agent 执行服务。
 */
@Service
public class ChatApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(ChatApplicationService.class);

    private final AgentExecutionService agentExecutionService;

    public ChatApplicationService(AgentExecutionService agentExecutionService) {
        this.agentExecutionService = agentExecutionService;
    }

    public ChatAnswer chat(ChatRequest request) throws Exception {
        PreparedChat prepared = prepareChat(request);
        logger.info("收到对话请求 - SessionId: {}, Question: {}, Mode: {}",
                prepared.sessionId(), prepared.question(), prepared.mode());
        AgentExecutionResult result = agentExecutionService.execute(new AgentExecutionService.AgentExecutionRequest(
                prepared.sessionId(),
                prepared.question(),
                prepared.mode(),
                false,
                AgentExecutionEvents.NOOP));
        return new ChatAnswer(result.conversationId(), result.answer(), result.reasoning());
    }

    /**
     * 校验流式问答请求。问题为空时抛出 IllegalArgumentException。
     */
    public PreparedChat prepareChat(ChatRequest request) {
        if (ChatRequestSupport.hasBlankQuestion(request)) {
            throw new IllegalArgumentException("问题内容不能为空");
        }
        return new PreparedChat(
                request.getId(),
                ChatRequestSupport.normalizedQuestion(request),
                TaskMode.CHAT);
    }

    public record PreparedChat(String sessionId, String question, TaskMode mode) {
    }
}
