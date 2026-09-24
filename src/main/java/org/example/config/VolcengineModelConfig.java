package org.example.config;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 火山方舟 OpenAI 兼容 Chat API 客户端。
 */
@Configuration
public class VolcengineModelConfig {

    @Bean
    public OpenAiApi volcengineOpenAiApi(
            VolcengineProperties properties,
            RestClient.Builder restClientBuilder) {
        validateApiKey(properties.getApiKey());
        return OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(properties.getChat().getBaseUrl()))
                .completionsPath(properties.getChat().getCompletionsPath())
                .apiKey(properties.getApiKey())
                .restClientBuilder(restClientBuilder)
                .build();
    }

    @Bean
    public OpenAiChatModel volcengineChatModel(
            OpenAiApi volcengineOpenAiApi,
            @Value("${agent.model}") String modelName) {
        return OpenAiChatModel.builder()
                .openAiApi(volcengineOpenAiApi)
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
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
