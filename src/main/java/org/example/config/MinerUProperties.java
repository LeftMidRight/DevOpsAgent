package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "mineru")
public class MinerUProperties {

    private boolean enabled;
    private String baseUrl = "";
    private String apiKey = "";
    private String tier = "standard";
    private String outputFormat = "structured_content";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration transferTimeout = Duration.ofSeconds(300);
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration maxPollDuration = Duration.ofSeconds(300);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getTier() {
        return tier;
    }

    public void setTier(String tier) {
        this.tier = tier;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getTransferTimeout() {
        return transferTimeout;
    }

    public void setTransferTimeout(Duration transferTimeout) {
        this.transferTimeout = transferTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getMaxPollDuration() {
        return maxPollDuration;
    }

    public void setMaxPollDuration(Duration maxPollDuration) {
        this.maxPollDuration = maxPollDuration;
    }
}
