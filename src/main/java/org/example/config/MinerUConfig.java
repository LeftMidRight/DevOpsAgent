package org.example.config;

import org.example.parsing.mineru.MinerUAgentClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MinerUProperties.class)
public class MinerUConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mineru", name = "enabled", havingValue = "true")
    RestClient minerUAgentRestClient(MinerUProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        String baseUrl = properties.getBaseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mineru", name = "enabled", havingValue = "true")
    MinerUAgentClient minerUAgentClient(MinerUProperties properties, RestClient minerUAgentRestClient) {
        return new MinerUAgentClient(properties, minerUAgentRestClient);
    }
}
