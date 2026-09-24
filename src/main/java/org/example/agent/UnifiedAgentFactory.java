package org.example.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建唯一业务 Agent：装配模型、任务策略提示词、统一工具集与 Hook。
 * 每个请求独立创建运行上下文及 Agent 实例，不共享可变运行状态。
 */
@Service
public class UnifiedAgentFactory {

    private static final Logger logger = LoggerFactory.getLogger(UnifiedAgentFactory.class);

    public static final String AGENT_NAME = "unified_business_agent";

    private static final ToolCallbackProvider NO_MCP_TOOLS = () -> new ToolCallback[0];

    private final AgentProperties properties;
    private final OpenAiApi volcengineOpenAiApi;
    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools queryMetricsTools;
    private final ObjectProvider<QueryLogsTools> queryLogsTools;
    private final ToolCallbackProvider mcpToolProvider;

    public UnifiedAgentFactory(
            AgentProperties properties,
            OpenAiApi volcengineOpenAiApi,
            DateTimeTools dateTimeTools,
            InternalDocsTools internalDocsTools,
            QueryMetricsTools queryMetricsTools,
            ObjectProvider<QueryLogsTools> queryLogsTools,
            @Autowired(required = false) @Qualifier("mcpAsyncToolCallbacks") ToolCallbackProvider mcpToolProvider) {
        this.properties = properties;
        this.volcengineOpenAiApi = volcengineOpenAiApi;
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.queryMetricsTools = queryMetricsTools;
        this.queryLogsTools = queryLogsTools;
        this.mcpToolProvider = mcpToolProvider != null ? mcpToolProvider : NO_MCP_TOOLS;
    }

    /**
     * 按任务策略创建 ChatModel。模型名来自显式配置。
     */
    public ChatModel createChatModel(AgentTaskPolicy policy) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(properties.getModel())
                .temperature(policy.temperature())
                .maxTokens(policy.maxTokens())
                .topP(policy.topP());
        if (properties.getThinking().isEnabled()) {
            optionsBuilder.extraBody(Map.of("thinking", Map.of("type", properties.getThinking().getType())));
        }
        return OpenAiChatModel.builder()
                .openAiApi(volcengineOpenAiApi)
                .defaultOptions(optionsBuilder.build())
                .build();
    }

    /**
     * 创建统一业务 Agent。工具集在本方法内完成注册、冲突检查与证据包装。
     */
    public ReactAgent createAgent(
            AgentTaskPolicy policy,
            ChatModel chatModel,
            AgentRunContext runContext,
            Hook... hooks) {
        ToolCallback[] wrapped = collectAndWrapTools(runContext);
        return ReactAgent.builder()
                .name(AGENT_NAME)
                .model(chatModel)
                .systemPrompt(policy.systemPrompt())
                .tools(wrapped)
                .hooks(hooks)
                .build();
    }

    private ToolCallback[] collectAndWrapTools(AgentRunContext runContext) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();

        for (ToolCallback callback : localToolCallbacks()) {
            ToolCallback previous = byName.putIfAbsent(callback.getToolDefinition().name(), callback);
            if (previous != null) {
                throw new IllegalStateException(
                        "本地工具名冲突: " + callback.getToolDefinition().name() + "，请在注册阶段显式处理");
            }
        }

        ToolCallback[] mcpCallbacks = mcpToolProvider == null
                ? new ToolCallback[0]
                : mcpToolProvider.getToolCallbacks();
        for (ToolCallback callback : mcpCallbacks == null ? new ToolCallback[0] : mcpCallbacks) {
            String name = callback.getToolDefinition().name();
            if (byName.containsKey(name)) {
                logger.warn("工具名冲突: {} - 保留已注册的本地工具，忽略 MCP 同名工具（Mock 与真实模式不应同时启用）", name);
                continue;
            }
            byName.put(name, callback);
        }

        List<ToolCallback> wrapped = new ArrayList<>();
        for (Map.Entry<String, ToolCallback> entry : byName.entrySet()) {
            String source = isLocalTool(entry.getKey()) ? "local" : "mcp";
            wrapped.add(new EvidenceCollectingToolCallback(entry.getValue(), runContext, source));
        }
        logger.info("统一工具注册完成 - 共 {} 个工具: {}", wrapped.size(),
                wrapped.stream().map(t -> t.getToolDefinition().name()).toList());
        return wrapped.toArray(new ToolCallback[0]);
    }

    private ToolCallback[] localToolCallbacks() {
        List<Object> toolObjects = new ArrayList<>(
                Arrays.asList(dateTimeTools, internalDocsTools, queryMetricsTools));
        QueryLogsTools logsTools = queryLogsTools.getIfAvailable();
        if (logsTools != null) {
            toolObjects.add(logsTools);
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects.toArray())
                .build()
                .getToolCallbacks();
    }

    private boolean isLocalTool(String toolName) {
        return DateTimeTools.TOOL_GET_CURRENT_DATETIME.equals(toolName)
                || InternalDocsTools.TOOL_QUERY_INTERNAL_DOCS.equals(toolName)
                || QueryMetricsTools.TOOL_QUERY_PROMETHEUS_ALERTS.equals(toolName)
                || QueryLogsTools.TOOL_QUERY_LOGS.equals(toolName)
                || QueryLogsTools.TOOL_GET_AVAILABLE_LOG_TOPICS.equals(toolName);
    }
}
