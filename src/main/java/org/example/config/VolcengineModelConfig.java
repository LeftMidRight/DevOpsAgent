package org.example.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * 火山方舟 OpenAI 兼容 Chat API 客户端。
 */
@Configuration
public class VolcengineModelConfig {

    @Bean
    public OpenAiChatModel volcengineChatModel(
            VolcengineProperties properties,
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            @Value("${agent.model}") String modelName) {
        validateApiKey(properties.getApiKey());
        return OpenAiChatModel.builder()
                .observationRegistry(observationRegistry)
                .meterRegistry(meterRegistry)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .baseUrl(trimTrailingSlash(properties.getChat().getBaseUrl()))
                        .apiKey(properties.getApiKey())
                        .timeout(Duration.ofMillis(properties.getChat().getTimeoutMs()))
                        .model(modelName)
                        .build())
                .build();
    }

    public static void validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || "your-api-key-here".equals(apiKey)) {
            throw new IllegalStateException(
                    "请设置环境变量 VOLCENGINE_API_KEY 或在 application.yml 中配置 volcengine.api-key");
        }
    }

    public static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
