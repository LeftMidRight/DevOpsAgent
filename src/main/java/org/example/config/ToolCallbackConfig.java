package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具 Provider 的空集合兜底：MCP 客户端未配置或不可用时，
 * 应用仍可启动，普通问答继续使用本地工具；需要日志能力的诊断
 * 在工具缺失时由模型如实说明，不把占位地址或失败结果当作有效数据。
 */
@Configuration
public class ToolCallbackConfig {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallbackConfig.class);

    @Bean
    @ConditionalOnProperty(name = "spring.ai.mcp.client.enabled", havingValue = "false")
    public ToolCallbackProvider emptyToolCallbackProvider() {
        logger.info("未检测到 MCP 工具 Provider，注册空工具集合（仅本地工具可用）");
        return () -> new ToolCallback[0];
    }
}
