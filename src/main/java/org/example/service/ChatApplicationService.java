package org.example.service;

import org.example.agent.AgentExecutionEvents;
import org.example.agent.AgentExecutionResult;
import org.example.agent.AgentExecutionService;
import org.example.agent.TaskMode;
import org.example.dto.ChatRequest;
import org.example.support.ChatRequestSupport;
import org.springframework.stereotype.Service;

/**
 * 同步聊天应用入口：参数归一化后委托统一 Agent 执行服务。
 */
@Service
public class ChatApplicationService {

    private final AgentExecutionService agentExecutionService;

    public ChatApplicationService(AgentExecutionService agentExecutionService) {
        this.agentExecutionService = agentExecutionService;
    }

    public AgentExecutionResult chat(ChatRequest request, TaskMode mode) throws Exception {
        return agentExecutionService.execute(new AgentExecutionService.AgentExecutionRequest(
                request.getId(),
                ChatRequestSupport.normalizedQuestion(request),
                mode,
                false,
                AgentExecutionEvents.NOOP));
    }
}
