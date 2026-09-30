package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.net.http.HttpClient;

/**
 * DashScope API 配置
 * 用于配置超时时间等参数
 */
@Configuration
public class DashScopeConfig {

    @Value("${volcengine.chat.timeout-ms:180000}")
    private long timeout;

    /**
     * 配置 RestClient.Builder，设置超时时间
     * Spring AI 会自动使用这个 Bean
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return RestClient.builder().requestFactory(factory);
    }
}
