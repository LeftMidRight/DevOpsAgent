package org.example.observability.loki;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "observability.loki")
public class LokiProperties {

    private String baseUrl = "http://localhost:3100";
    private Duration timeout = Duration.ofSeconds(10);
    private int defaultLimit = 20;
    private int maxLimit = 100;
    private int maxMessageChars = 500;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getMaxLimit() {
        return maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }

    public int getMaxMessageChars() {
        return maxMessageChars;
    }

    public void setMaxMessageChars(int maxMessageChars) {
        this.maxMessageChars = maxMessageChars;
    }
}
