package org.example.observability.langfuse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 只作为 Trace 后端的开关。密钥不放在这里，走 OTLP 环境变量。
 */
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    private boolean enabled;
    private String environment = "dev";
    private String appVersion = "unknown";
    /** 为 true 时把工具参数和工具结果写入 Trace。默认关闭，避免上传日志和文档正文。 */
    private boolean includeToolContent;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public boolean isIncludeToolContent() {
        return includeToolContent;
    }

    public void setIncludeToolContent(boolean includeToolContent) {
        this.includeToolContent = includeToolContent;
    }
}
