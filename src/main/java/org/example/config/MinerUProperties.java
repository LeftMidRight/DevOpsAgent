package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "mineru")
public class MinerUProperties {

    private boolean enabled;
    private String baseUrl = "https://mineru.net";
    private String language = "ch";
    private boolean enableTable = true;
    private boolean ocr;
    private boolean enableFormula = true;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofSeconds(2);
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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isEnableTable() {
        return enableTable;
    }

    public void setEnableTable(boolean enableTable) {
        this.enableTable = enableTable;
    }

    public boolean isOcr() {
        return ocr;
    }

    public void setOcr(boolean ocr) {
        this.ocr = ocr;
    }

    public boolean isEnableFormula() {
        return enableFormula;
    }

    public void setEnableFormula(boolean enableFormula) {
        this.enableFormula = enableFormula;
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
