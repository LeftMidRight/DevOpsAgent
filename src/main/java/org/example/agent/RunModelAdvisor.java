package org.example.agent;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.HashMap;

/** 请求独占的 Advisor：对工具循环的每次模型调用执行预算检查与增量输出。 */
final class RunModelAdvisor implements CallAdvisor, StreamAdvisor {

    private final AgentRunContext run;
    private final BudgetEnforcementHook budget;
    private final AgentExecutionEvents events;
    private final StreamingAnswerCollector collector;

    RunModelAdvisor(AgentRunContext run, BudgetEnforcementHook budget,
                    AgentExecutionEvents events, StreamingAnswerCollector collector) {
        this.run = run;
        this.budget = budget;
        this.events = events;
        this.collector = collector;
    }

    @Override
    public String getName() {
        return "RunModelAdvisor";
    }

    @Override
    public int getOrder() {
        return ToolCallingAdvisor.DEFAULT_ORDER + 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        before(request);
        ChatClientResponse response = chain.nextCall(request);
        run.rejectLateResult();
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            before(request);
            StringBuilder reasoning = new StringBuilder();
            Flux<ChatClientResponse> stream = chain.nextStream(request).doOnNext(part -> {
                run.rejectLateResult();
                ChatResponse response = part.chatResponse();
                if (response == null || response.getResult() == null) {
                    return;
                }
                AssistantMessage output = response.getResult().getOutput();
                String content = output.getText();
                if (content != null && !content.isEmpty()) {
                    collector.onStreamingChunk(content);
                    events.onContentDelta(content);
                }
                String delta = ReasoningContentExtractor.extract(output);
                if (delta != null) {
                    // SDK 返回累计推理内容，转换为 SSE 增量。
                    if (delta.startsWith(reasoning.toString())) {
                        delta = delta.substring(reasoning.length());
                    }
                    reasoning.append(delta);
                    if (!delta.isEmpty()) {
                        collector.onReasoningChunk(delta);
                        events.onReasoningDelta(delta);
                    }
                }
            });
            return new ChatClientMessageAggregator().aggregateChatClientResponse(stream, response -> {
                ChatResponse chat = response.chatResponse();
                if (chat == null || chat.getResult() == null) {
                    throw new IllegalStateException("模型没有返回内容");
                }
                AssistantMessage output = chat.getResult().getOutput();
                if (reasoning.length() > 0) {
                    var metadata = new HashMap<>(output.getMetadata());
                    metadata.put(ReasoningContentExtractor.METADATA_KEY, reasoning.toString());
                    output = AssistantMessage.builder().content(output.getText())
                            .properties(metadata).toolCalls(output.getToolCalls()).build();
                }
                run.rejectLateResult();
                collector.onModelFinished(output.getText());
                collector.onReasoningFinished(output);
            });
        });
    }

    private void before(ChatClientRequest request) {
        budget.beforeModel(request.prompt().getInstructions());
    }
}
