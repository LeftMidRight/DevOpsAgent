package org.example.observability.langfuse;

import org.springframework.ai.tool.observation.ToolCallingContentObservationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 2.0.1 没有 spring.ai.tools.observations.include-content 配置项。
 * 工具参数和结果是否进入 Trace，由 langfuse.include-tool-content 控制官方 Filter。
 */
@Configuration
public class LangfuseObservationConfig {

    @Bean
    @ConditionalOnProperty(prefix = "langfuse", name = "include-tool-content", havingValue = "true")
    ToolCallingContentObservationFilter toolCallingContentObservationFilter() {
        return new ToolCallingContentObservationFilter();
    }
}
